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
        profiles: List<String> = emptyList(),
    ): JvmReplayHarness {
        val project = MavenProjectInfo(
            projectDir = Path.of("").toAbsolutePath().normalize(),
            testClassesDir = testClassesDir,
            classesDir = classesDir,
            classpathEntries = emptyList(),
            systemProperties = emptyMap(),
            argLine = argLine,
            systemPropertiesFile = null,
            profiles = profiles,
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

    @Test
    fun `argLine quoted token with spaces stays one JVM argument`() {
        // javaagent с пробелом в пути — типичный jacoco/агент на Windows; ProcessBuilder
        // передаёт токены без shell: кавычки съедает токенизатор, пробел остаётся внутри
        val h = harness(argLine = """-javaagent:"C:\Program Files\agent.jar" -Xmx512m""")

        val cmd = h.buildCommand(listOf(polluter), statefulVictim, 3, Path.of("r.json"))

        val jvmArgs = cmd.subList(1, cmd.indexOf("-cp"))
        assertEquals(listOf("""-javaagent:C:\Program Files\agent.jar""", "-Xmx512m"), jvmArgs)
    }

    @Test
    fun `argLine escaped quote inside token is preserved literally`() {
        val h = harness(argLine = """-Dquote="a \"b\" c" -Xss2m""")

        val cmd = h.buildCommand(listOf(polluter), statefulVictim, 3, Path.of("r.json"))

        val jvmArgs = cmd.subList(1, cmd.indexOf("-cp"))
        assertEquals(listOf("""-Dquote=a "b" c""", "-Xss2m"), jvmArgs)
    }

    @Test
    fun `activated profiles go into probe environment`() {
        // спека §4.2: «activated profiles → env»; эффекты профилей на pom уже
        // интерполированы в effective-pom — это канал для тестов, читающих окружение
        assertEquals(
            mapOf("MAVEN_ACTIVE_PROFILES" to "ci,db"),
            harness(profiles = listOf("ci", "db")).probeEnvironment(),
        )
        assertEquals(emptyMap<String, String>(), harness().probeEnvironment())
    }

    @Test
    fun `buildCommand - class-level prefix ref renders fqcn without hash`() {
        // repro-команда вердикта (Task 5.1) допускает polluter-записи БЕЗ #method
        // (replay запускает весь класс): «FQCN#» ломало бы команду зонда.
        val cmd = harness().buildCommand(
            listOf(TestRef("com.example.PolluterTest", "")),
            statefulVictim, 3, Path.of("report.json"),
        )
        val i = cmd.indexOf("--prefix")
        assertEquals("com.example.PolluterTest", cmd[i + 1])
    }

    @Test
    fun `command puts project classpath first and probe cp last`() {
        // блокирующее правило спеки §4.2: classpath проекта ПЕРВЫМ, replay-jar ПОСЛЕДНИМ
        val cmd = harness().buildCommand(listOf(polluter), statefulVictim, 3, Path.of("dummy-report.json"))

        val cp = cmd[cmd.indexOf("-cp") + 1]
        val entries = cp.split(java.io.File.pathSeparator)
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
        assertEquals(Path.of("dummy-report.json").toString(), cmd[cmd.indexOf("--report") + 1])
        // durations пусты -> clamp(x*5, 30s, 180s) даёт нижнюю границу 30s
        assertEquals("30", cmd[cmd.indexOf("--victim-timeout") + 1])
    }
}
