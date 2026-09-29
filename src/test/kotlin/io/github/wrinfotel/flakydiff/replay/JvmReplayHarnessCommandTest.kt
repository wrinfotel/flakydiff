package io.github.wrinfotel.flakydiff.replay

import io.github.wrinfotel.flakydiff.reader.TestRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.commons.JUnitException
import org.junit.platform.engine.TestEngine
import org.junit.platform.launcher.core.LauncherFactory
import java.nio.file.Path

/**
 * Сборка команды зонда без запуска JVM (unit-скорость): формулы таймаутов,
 * argLine, classpath, прокидывание свойств effective-pom.
 */
class JvmReplayHarnessCommandTest {

    private fun entryOf(cls: Class<*>): String =
        Path.of(cls.protectionDomain.codeSource.location.toURI()).toString()

    private val testClassesDir = Path.of(entryOf(JvmReplayHarnessCommandTest::class.java))
    private val classesDir = Path.of(entryOf(io.github.wrinfotel.flakydiff.reader.TestRun::class.java))

    private fun probeClasspath(): List<Path> = listOf(
        LauncherFactory::class.java,
        JupiterTestEngine::class.java,
        org.junit.jupiter.api.Test::class.java,
        org.opentest4j.AssertionFailedError::class.java,
        TestEngine::class.java,
        JUnitException::class.java,
        kotlin.Unit::class.java,
    ).map { Path.of(entryOf(it)) }.distinct()

    private fun harness(
        durationsMs: Map<TestRef, Long> = emptyMap(),
        victimTimeoutMs: Long? = null,
        argLine: String? = null,
    ): JvmReplayHarness {
        val project = MavenProjectInfo(
            projectDir = Path.of("").toAbsolutePath().normalize(),
            testClassesDir = testClassesDir,
            classesDir = classesDir,
            classpathEntries = emptyList(),
            systemProperties = emptyMap(),
            argLine = argLine,
            systemPropertiesFile = null,
            profiles = emptyList(),
            warnings = emptyList(),
        )
        return JvmReplayHarness(
            project = project,
            probeClasspath = probeClasspath(),
            durationsMs = durationsMs,
            victimTimeoutMs = victimTimeoutMs,
        )
    }

    private val polluter = TestRef("io.github.wrinfotel.flakydiff.replay.FakeStatePolluter", "makeDirty")
    private val statefulVictim = TestRef("io.github.wrinfotel.flakydiff.replay.FakeStatefulVictim", "failsWhenDirty")

    @Test
    fun `victim timeout is xml duration x5 clamped`() {
        // спека §4.2: victimTimeout = clamp(duration×5, 30s, 180s) — умножение ОДИН раз
        val h = harness(durationsMs = mapOf(statefulVictim to 10_000))

        val cmd = h.buildCommand(listOf(polluter), statefulVictim, 3, Path.of("r.json"))

        assertEquals("50", cmd[cmd.indexOf("--victim-timeout") + 1], "10s × 5 = 50s (не ×25 → 180s)")
    }

    @Test
    fun `victim timeout x5 stays inside clamp bounds`() {
        // 60s × 5 = 300s → потолок 180s; 100ms × 5 = 0.5s → пол 30s
        val over = harness(durationsMs = mapOf(statefulVictim to 60_000))
            .buildCommand(listOf(polluter), statefulVictim, 3, Path.of("r.json"))
        assertEquals("180", over[over.indexOf("--victim-timeout") + 1])

        val under = harness(durationsMs = mapOf(statefulVictim to 100))
            .buildCommand(listOf(polluter), statefulVictim, 3, Path.of("r.json"))
        assertEquals("30", under[under.indexOf("--victim-timeout") + 1])
    }

    @Test
    fun `explicit victim timeout overrides xml duration`() {
        val h = harness(durationsMs = mapOf(statefulVictim to 10_000), victimTimeoutMs = 45_000)

        val cmd = h.buildCommand(listOf(polluter), statefulVictim, 3, Path.of("r.json"))

        assertEquals("45", cmd[cmd.indexOf("--victim-timeout") + 1])
    }
}
