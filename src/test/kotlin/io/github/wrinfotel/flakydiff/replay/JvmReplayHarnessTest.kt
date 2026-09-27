package io.github.wrinfotel.flakydiff.replay

import io.github.wrinfotel.flakydiff.reader.TestRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.commons.JUnitException
import org.junit.platform.engine.TestEngine
import org.junit.platform.launcher.core.LauncherFactory
import java.io.File
import java.nio.file.Path

/**
 * Каждый вызов harness = новый процесс (свежая JVM): prefix → victim×N,
 * состояние между зондами не переносится (спека §4.2, блокирующее правило).
 */
class JvmReplayHarnessTest {

    private fun entryOf(cls: Class<*>): String =
        Path.of(cls.protectionDomain.codeSource.location.toURI()).toString()

    private val testClassesDir = Path.of(entryOf(JvmReplayHarnessTest::class.java))
    private val classesDir = Path.of(entryOf(io.github.wrinfotel.flakydiff.reader.TestRun::class.java))

    /** Сторонние jars — как replay-jar в реальном использовании, последними в cp. */
    private fun probeClasspath(): List<Path> = listOf(
        LauncherFactory::class.java,      // junit-platform-launcher
        JupiterTestEngine::class.java,    // junit-jupiter-engine
        org.junit.jupiter.api.Test::class.java,
        org.opentest4j.AssertionFailedError::class.java,
        TestEngine::class.java,
        JUnitException::class.java,
        kotlin.Unit::class.java,
    ).map { Path.of(entryOf(it)) }.distinct()

    private fun harness(victimTimeoutMs: Long? = null, probeTimeoutMs: Long? = null): JvmReplayHarness {
        val project = MavenProjectInfo(
            projectDir = Path.of("").toAbsolutePath().normalize(),
            testClassesDir = testClassesDir,
            classesDir = classesDir,
            classpathEntries = emptyList(),
            systemProperties = emptyMap(),
            argLine = null,
            systemPropertiesFile = null,
            profiles = emptyList(),
            warnings = emptyList(),
        )
        return JvmReplayHarness(
            project = project,
            probeClasspath = probeClasspath(),
            victimTimeoutMs = victimTimeoutMs,
            probeTimeoutMs = probeTimeoutMs,
        )
    }

    private val polluter = TestRef("io.github.wrinfotel.flakydiff.replay.FakeStatePolluter", "makeDirty")
    private val statefulVictim = TestRef("io.github.wrinfotel.flakydiff.replay.FakeStatefulVictim", "failsWhenDirty")
    private val hangingVictim = TestRef("io.github.wrinfotel.flakydiff.replay.FakeHangingVictim", "hangs")
    private val passing = TestRef("io.github.wrinfotel.flakydiff.replay.FakePassing", "alwaysPasses")

    @Test
    fun `a dirty prefix reproduces victim failure`() {
        val result = harness().replay(listOf(polluter), statefulVictim)

        assertTrue(result is ReplayResult.Reproduced, "got: $result")
        result as ReplayResult.Reproduced
        assertTrue(result.failures >= 2, "failures=${result.failures}")
        assertEquals(3, result.attempts)
        assertEquals("java.lang.AssertionError", result.failure.type)
        assertEquals("polluted", result.failure.message)
    }

    @Test
    fun `b clean prefix does not reproduce`() {
        val result = harness().replay(emptyList(), statefulVictim)

        assertEquals(ReplayResult.NotReproduced(passes = 3, attempts = 3), result)
    }

    @Test
    fun `c victim timeout becomes infra error quickly`() {
        val startedAt = System.nanoTime()
        val result = harness(victimTimeoutMs = 2_000).replay(emptyList(), hangingVictim)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(result is ReplayResult.InfraError, "got: $result")
        assertTrue((result as ReplayResult.InfraError).cause.contains("timeout"), "cause: ${result.cause}")
        assertTrue(elapsedMs < 15_000, "watchdog обязан сработать быстро, elapsed=${elapsedMs}ms")
    }

    @Test
    fun `d probe isolation dirty state does not leak into next probe JVM`() {
        val h = harness()

        val first = h.replay(listOf(polluter), statefulVictim)
        assertTrue(first is ReplayResult.Reproduced, "first: $first")

        val second = h.replay(emptyList(), statefulVictim)
        assertEquals(ReplayResult.NotReproduced(passes = 3, attempts = 3), second, "состояние протекло между зондами")
    }

    @Test
    fun `e probe timeout kills the whole probe process`() {
        val result = harness(probeTimeoutMs = 1).replay(emptyList(), passing)

        assertTrue(result is ReplayResult.InfraError, "got: $result")
        assertTrue((result as ReplayResult.InfraError).cause.contains("probe timeout"), "cause: ${result.cause}")
    }

    @Test
    fun `command puts project classpath first and probe cp last`() {
        val report = Path.of("dummy-report.json")

        val cmd = harness().buildCommand(listOf(polluter), statefulVictim, 3, report)

        val cp = cmd[cmd.indexOf("-cp") + 1]
        val entries = cp.split(File.pathSeparator)
        assertEquals(testClassesDir.toString(), entries.first(), "target/test-classes обязан быть первым")
        assertEquals(classesDir.toString(), entries[1], "target/classes — вторым")
        val probeCp = probeClasspath().map { it.toString() }
        assertEquals(probeCp, entries.takeLast(probeCp.size), "probe-cp (в реале replay-jar) — последним")

        assertEquals(
            "io.github.wrinfotel.flakydiff.replay.FakeStatePolluter#makeDirty",
            cmd[cmd.indexOf("--prefix") + 1],
        )
        assertEquals(
            "io.github.wrinfotel.flakydiff.replay.FakeStatefulVictim#failsWhenDirty",
            cmd[cmd.indexOf("--victim") + 1],
        )
        assertEquals("3", cmd[cmd.indexOf("--repeat") + 1])
        assertEquals(report.toString(), cmd[cmd.indexOf("--report") + 1])
        // durations пусты -> clamp(x*5, 30s, 180s) даёт нижнюю границу 30s
        assertEquals("30", cmd[cmd.indexOf("--victim-timeout") + 1])
    }
}
