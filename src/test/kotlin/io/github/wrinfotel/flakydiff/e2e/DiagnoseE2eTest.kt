package io.github.wrinfotel.flakydiff.e2e

import io.github.wrinfotel.flakydiff.ddmin.Diagnosis
import io.github.wrinfotel.flakydiff.ddmin.FakeHarness
import io.github.wrinfotel.flakydiff.ddmin.diagnose
import io.github.wrinfotel.flakydiff.fixture.Scenario
import io.github.wrinfotel.flakydiff.fixture.generateFixture
import io.github.wrinfotel.flakydiff.reader.OrderSource
import io.github.wrinfotel.flakydiff.reader.ReadOptions
import io.github.wrinfotel.flakydiff.reader.Status
import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.reader.TestRun
import io.github.wrinfotel.flakydiff.reader.readReports
import io.github.wrinfotel.flakydiff.replay.JvmReplayHarness
import io.github.wrinfotel.flakydiff.replay.ReplayResult
import io.github.wrinfotel.flakydiff.replay.defaultMvnCommand
import io.github.wrinfotel.flakydiff.replay.prepareProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * Обязательная e2e-матрица спеки §5 (план Task 4.2) — 11 кейсов, НЕ сокращать.
 *
 * Кейсы 3, 4, 7, 8, 11 — unit-скорость: XML-ресурсы + fake-harness (план прямо
 * разрешает). Кейсы 1, 2, 5, 6, 9, 10 — реальные зонды: генерируется fixture,
 * в нём прогоняется `mvn test` (записанный порядок), затем полный пайплайн
 * reader → JvmReplayHarness → diagnose.
 */
@Tag("integration")
class DiagnoseE2eTest {

    @TempDir
    lateinit var tmp: Path

    // ---------- helpers ----------

    private fun runMvnIn(dir: Path, vararg args: String): Int {
        val proc = ProcessBuilder(defaultMvnCommand + args.toList())
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(600, TimeUnit.SECONDS), "mvn завис; output:\n$output")
        return proc.exitValue()
    }

    /** Сторонние jars — как replay-jar в реальном использовании, последними в cp. */
    private fun probeClasspath(): List<Path> = listOf(
        TestRef::class.java, // main-классы flakydiff (рядом и ReplayMain)
        org.junit.platform.launcher.core.LauncherFactory::class.java,
        org.junit.jupiter.engine.JupiterTestEngine::class.java,
        org.junit.jupiter.api.Test::class.java,
        org.junit.jupiter.params.ParameterizedTest::class.java,
        org.opentest4j.AssertionFailedError::class.java,
        org.junit.platform.engine.TestEngine::class.java,
        org.junit.platform.commons.JUnitException::class.java,
        kotlin.Unit::class.java,
    ).map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct()

    private fun fixtureHarness(fixtureDir: Path, durations: Map<TestRef, Long> = emptyMap()): JvmReplayHarness {
        val project = prepareProject(fixtureDir, fixtureDir.resolve("target").resolve("flakydiff-cache"))
        return JvmReplayHarness(project, probeClasspath(), durations)
    }

    private fun prefixBefore(run: TestRun, victim: TestRef): List<TestRef> =
        run.entries.takeWhile { it.ref != victim }.map { it.ref }

    private fun writeReport(dir: Path, fileName: String, xml: String, mtimeMs: Long) {
        val file = dir.resolve(fileName)
        Files.writeString(file, xml)
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMs))
    }

    private fun passingSuite(cls: String, methods: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<testsuite name=\"$cls\" time=\"0.01\" tests=\"${methods.size}\" errors=\"0\" skipped=\"0\" failures=\"0\">\n")
        for (m in methods) append("  <testcase name=\"$m\" classname=\"$cls\" time=\"0.005\"/>\n")
        append("</testsuite>\n")
    }

    private fun failedSuite(cls: String, method: String, message: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<testsuite name=\"$cls\" time=\"0.02\" tests=\"1\" errors=\"0\" skipped=\"0\" failures=\"1\">\n")
        append("  <testcase name=\"$method\" classname=\"$cls\" time=\"0.02\">\n")
        append("    <failure message=\"$message\" type=\"java.lang.AssertionError\">stack</failure>\n")
        append("  </testcase>\n")
        append("</testsuite>\n")
    }

    private fun ballast(classes: Int, testsPerClass: Int = 6): List<Scenario> =
        (1..classes).map { Scenario.SimplePassing("Ballast%02d".format(it), testsPerClass) }

    /** Case 6 проверяется по имени класса диагноза: UNSUPPORTED появится в Task 4.3. */
    private val Diagnosis.isUnsupported: Boolean
        get() = this::class.simpleName == "Unsupported"

    // ---------- реальные зонды ----------

    @Test
    fun `case01 - single polluter is found as ORDER-DEPENDENCY`() {
        val fixture = generateFixture(
            tmp.resolve("fixture-case01"),
            ballast(30) + listOf(
                Scenario.PolluterPollutes("Polluter", "v1"),
                Scenario.Victim("Victim", "v1"),
            ),
        )
        assertTrue(runMvnIn(fixture.dir, "-q", "test") != 0, "recorded прогон обязан упасть: жертва загрязнена")

        val read = readReports(fixture.reportsDir())
        assertTrue(read.errors.isEmpty(), "errors: ${read.errors}")
        val victim = TestRef(fixture.classes[31].className, "shouldWork")
        val victimEntry = read.run.entries.last { it.ref == victim }
        assertEquals(Status.FAILED, victimEntry.status, "в recorded прогоне жертва обязана быть FAILED")
        val prefix = prefixBefore(read.run, victim)
        assertEquals(181, prefix.size, "балласт (180) + polluter до жертвы")

        val harness = fixtureHarness(fixture.dir, read.run.entries.associate { it.ref to it.durationMs })
        val d = diagnose(harness, victim, prefix)

        assertTrue(d is Diagnosis.OrderDependency, "got: $d")
        d as Diagnosis.OrderDependency
        assertEquals(
            listOf(TestRef(fixture.classes[30].className, "pollutes")),
            d.polluters,
            "минимальный набор — ровно polluter",
        )
        assertEquals(5, d.confirmationAttempts)
        assertTrue(d.confirmationFailures >= 4, "confirmation: ${d.confirmationFailures}/${d.confirmationAttempts}")
    }

    @Test
    fun `case02 - two polluters - exactly one is found (fixed behavior)`() {
        val fixture = generateFixture(
            tmp.resolve("fixture-case02"),
            ballast(30) + listOf(
                Scenario.PolluterPollutes("PolluterA", "v2"),
                Scenario.PolluterPollutes("PolluterB", "v2"),
                Scenario.Victim("Victim", "v2"),
            ),
        )
        assertTrue(runMvnIn(fixture.dir, "-q", "test") != 0, "recorded прогон обязан упасть")

        val read = readReports(fixture.reportsDir())
        val polluterClasses = setOf(fixture.classes[30].className, fixture.classes[31].className)
        val victim = TestRef(fixture.classes[32].className, "shouldWork")

        val harness = fixtureHarness(fixture.dir, read.run.entries.associate { it.ref to it.durationMs })
        val d = diagnose(harness, victim, prefixBefore(read.run, victim))

        assertTrue(d is Diagnosis.OrderDependency, "got: $d")
        d as Diagnosis.OrderDependency
        assertEquals(
            1,
            d.polluters.size,
            "зафиксированное поведение §4.3: 1-minimal содержит ОДНОГО из двух: ${d.polluters}",
        )
        assertTrue(d.polluters[0].testClass in polluterClasses, "polluter: ${d.polluters}")
    }

    @Test
    fun `case05 - recorded failure does not reproduce after full prefix`() {
        val fixture = generateFixture(
            tmp.resolve("fixture-case05"),
            listOf(
                Scenario.SimplePassing("Ballast", 4),
                Scenario.Victim("Victim", "v5"),
            ),
        )
        assertEquals(0, runMvnIn(fixture.dir, "-q", "test"), "fixture без polluter обязан быть зелёным")

        // Recorded XML заявляет падение жертвы после балласта (race/время recorded-прогона);
        // реальные зонды по fixture-проекту это падение не воспроизводят.
        val reports = tmp.resolve("reports-case05")
        Files.createDirectories(reports)
        val t = System.currentTimeMillis() - 60_000
        writeReport(
            reports,
            "TEST-io.github.wrinfotel.fixture.T01_BallastTest.xml",
            passingSuite("io.github.wrinfotel.fixture.T01_BallastTest", listOf("t1", "t2", "t3", "t4")),
            t,
        )
        writeReport(
            reports,
            "TEST-io.github.wrinfotel.fixture.T02_VictimTest.xml",
            failedSuite("io.github.wrinfotel.fixture.T02_VictimTest", "shouldWork", "race in recorded run"),
            t + 5_000,
        )

        val read = readReports(reports)
        val victim = TestRef("io.github.wrinfotel.fixture.T02_VictimTest", "shouldWork")
        val prefix = prefixBefore(read.run, victim)
        assertEquals(4, prefix.size)

        val harness = fixtureHarness(fixture.dir)
        val d = diagnose(harness, victim, prefix)

        assertTrue(d is Diagnosis.NotReproduced, "got: $d")
        d as Diagnosis.NotReproduced
        assertEquals(3, d.passes, "жертва в реальном зонде проходит чисто")
        assertEquals(3, d.attempts)
    }

    @Test
    fun `case06 - parameterized victim is UNSUPPORTED with the test name in diagnostics`() {
        val fixture = generateFixture(
            tmp.resolve("fixture-case06"),
            listOf(
                Scenario.Parameterized("Param"),
                Scenario.SimplePassing("Ballast", 2),
                Scenario.Victim("Victim", "v6"),
            ),
        )
        assertEquals(0, runMvnIn(fixture.dir, "-q", "test"))

        val read = readReports(fixture.reportsDir())
        val paramVictim = read.run.entries.map { it.ref }
            .firstOrNull { it.method.contains('(') || it.method.contains('[') }
        assertTrue(
            paramVictim != null,
            "в recorded XML обязан быть display-name параметризованного теста: " +
                read.run.entries.joinToString { it.ref.method },
        )
        val harness = fixtureHarness(fixture.dir)

        val d = diagnose(harness, paramVictim!!, emptyList())

        assertTrue(d.isUnsupported, "got: $d")
        assertTrue(d.toString().contains("check(int)[1]"), "имя теста в диагностике: $d")
    }

    @Test
    fun `case09 - infra failure mid-ddmin stops with INFRA-BROKEN`() {
        val fixture = generateFixture(
            tmp.resolve("fixture-case09"),
            listOf(
                // EnvSetup готовит «среду» (static-инициализатор при загрузке класса):
                // записанный прогон и шаг 1 проходят; зонд ddmin без EnvSetup обнажает
                // сломанный @BeforeAll → честный InfraError, сужения нет (§4.3).
                Scenario.GuardSetup("EnvSetup", "fd.guard.case09"),
                Scenario.SimplePassing("Ballast", 2),
                Scenario.BeforeAllBroken("Broken", "fd.guard.case09"),
                Scenario.PolluterPollutes("Polluter", "v9"),
                Scenario.Victim("Victim", "v9"),
            ),
        )
        assertTrue(runMvnIn(fixture.dir, "-q", "test") != 0, "recorded прогон обязан упасть: жертва загрязнена")

        val read = readReports(fixture.reportsDir())
        val victim = TestRef(fixture.classes[4].className, "shouldWork")

        val harness = fixtureHarness(fixture.dir, read.run.entries.associate { it.ref to it.durationMs })
        val d = diagnose(harness, victim, prefixBefore(read.run, victim))

        assertTrue(d is Diagnosis.InfraBroken, "got: $d")
        assertTrue((d as Diagnosis.InfraBroken).cause.isNotBlank(), "диагностика обязана прилагаться")
    }

    @Test
    fun `case10 - confirmation 3 of 5 lowers to UNCONFIRMED`() {
        val fixture = generateFixture(
            tmp.resolve("fixture-case10"),
            listOf(
                Scenario.SimplePassing("Ballast", 2),
                Scenario.PolluterPollutes("Polluter", "v10"),
                Scenario.UnconfirmedVictim("Victim", "v10"),
            ),
        )
        runMvnIn(fixture.dir, "-q", "test") // recorded: жертва на 1-м запуске ещё чиста

        val read = readReports(fixture.reportsDir())
        val victim = TestRef(fixture.classes[2].className, "shouldWork")

        val harness = fixtureHarness(fixture.dir, read.run.entries.associate { it.ref to it.durationMs })
        val d = diagnose(harness, victim, prefixBefore(read.run, victim))

        assertTrue(d is Diagnosis.Unconfirmed, "got: $d")
        d as Diagnosis.Unconfirmed
        assertEquals(
            listOf(TestRef(fixture.classes[1].className, "pollutes")),
            d.polluters,
            "ddmin находит polluter, confirmation 3/5 понижает вердикт",
        )
        assertEquals(3, d.confirmationFailures, "граничный счёт: ровно 3 падения из 5")
        assertEquals(5, d.confirmationAttempts)
    }

    // ---------- unit-скорость: XML + fake-harness ----------

    @Test
    fun `case03 - victim unstable alone 2 of 3 - NOT-ISOLATED with flag`() {
        val reports = tmp.resolve("reports-case03")
        Files.createDirectories(reports)
        val t = System.currentTimeMillis() - 60_000
        writeReport(
            reports,
            "TEST-io.github.wrinfotel.fixture.T01_BallastTest.xml",
            passingSuite("io.github.wrinfotel.fixture.T01_BallastTest", listOf("t1", "t2")),
            t,
        )
        writeReport(
            reports,
            "TEST-io.github.wrinfotel.fixture.T02_VictimTest.xml",
            failedSuite("io.github.wrinfotel.fixture.T02_VictimTest", "shouldWork", "flaky victim"),
            t + 5_000,
        )

        val read = readReports(reports)
        assertTrue(read.errors.isEmpty(), "errors: ${read.errors}")
        val victim = TestRef("io.github.wrinfotel.fixture.T02_VictimTest", "shouldWork")
        val prefix = prefixBefore(read.run, victim)
        assertTrue(prefix.isNotEmpty(), "у жертвы обязаны быть предшественники из XML")

        val h = FakeHarness.fromBooleans(listOf(true, false, false), victim) // true = упал; 1/3 упал → 2/3 прошло → WEAK
        val d = diagnose(h, victim, prefix)

        assertTrue(d is Diagnosis.NotIsolated, "got: $d")
        assertEquals(
            true,
            (d as Diagnosis.NotIsolated).victimUnstableInIsolation,
            "флаг victim_unstable_in_isolation ⟺ WEAK («2/3 прошло»)",
        )
        assertEquals(1, h.calls.size, "после шага 0 диагностика останавливается: ${h.calls}")
    }

    @Test
    fun `case04 - victim fails alone - NOT-ISOLATED without flag`() {
        val reports = tmp.resolve("reports-case04")
        Files.createDirectories(reports)
        val t = System.currentTimeMillis() - 60_000
        writeReport(
            reports,
            "TEST-io.github.wrinfotel.fixture.T01_BallastTest.xml",
            passingSuite("io.github.wrinfotel.fixture.T01_BallastTest", listOf("t1")),
            t,
        )
        writeReport(
            reports,
            "TEST-io.github.wrinfotel.fixture.T02_VictimTest.xml",
            failedSuite("io.github.wrinfotel.fixture.T02_VictimTest", "shouldWork", "always broken"),
            t + 5_000,
        )

        val read = readReports(reports)
        val victim = TestRef("io.github.wrinfotel.fixture.T02_VictimTest", "shouldWork")
        val prefix = prefixBefore(read.run, victim)

        val h = FakeHarness.fromBooleans(listOf(true, true, true), victim) // 0/3 прошло → FAILS
        val d = diagnose(h, victim, prefix)

        assertTrue(d is Diagnosis.NotIsolated, "got: $d")
        assertEquals(
            false,
            (d as Diagnosis.NotIsolated).victimUnstableInIsolation,
            "тест просто падает сам по себе — БЕЗ флага",
        )
        assertEquals(1, h.calls.size, "после шага 0 диагностика останавливается: ${h.calls}")
    }

    @Test
    fun `case07 - rerun-dedup - victim is not its own predecessor and markedFlaky is set`() {
        val reports = tmp.resolve("reports-case07")
        Files.createDirectories(reports)
        val xml = javaClass.getResourceAsStream("/xml/rerun-flaky.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        // тот же ref в двух файлах (как после rerun-прогонов): dedup обязан оставить первый прогон
        Files.writeString(reports.resolve("TEST-com.acme.FlakeTest.xml"), xml)
        Files.writeString(reports.resolve("TEST-com.acme.FlakeTest-rerun.xml"), xml)

        val read = readReports(reports)
        assertTrue(read.errors.isEmpty(), "errors: ${read.errors}")
        val victim = TestRef("com.acme.FlakeTest", "rerunFail")

        assertEquals(1, read.run.entries.count { it.ref == victim }, "жертва встречается один раз: ${read.run.entries}")
        val prefix = prefixBefore(read.run, victim)
        assertTrue(victim !in prefix, "жертва не должна быть предшественником самой себя")
        assertTrue(victim in read.run.markedFlaky, "markedFlakyInRun обязан содержать rerun-жертву")
        assertTrue(TestRef("com.acme.FlakeTest", "flakyPass") in read.run.markedFlaky)
    }

    @Test
    fun `case08 - victim fails 1 of 3 after full prefix - WEAK stops ddmin with honest rate`() {
        val victim = TestRef("io.github.wrinfotel.fixture.T02_VictimTest", "shouldWork")
        val polluter = TestRef("io.github.wrinfotel.fixture.T01_PolluterTest", "pollutes")
        val h = FakeHarness { prefix, _, _ ->
            if (prefix.isEmpty()) ReplayResult.NotReproduced(3, 3) // шаг 0: изолированно чисто
            else ReplayResult.NotReproduced(2, 3) // шаг 1: упал 1 из 3 → WEAK
        }

        val d = diagnose(h, victim, listOf(polluter))

        assertTrue(d is Diagnosis.NotReproduced, "got: $d")
        d as Diagnosis.NotReproduced
        assertEquals(2, d.passes, "честный rate: 2 из 3 прошло")
        assertEquals(3, d.attempts)
        assertEquals(2, h.calls.size, "WEAK на шаге 1 останавливает — ddmin не сужает: ${h.calls}")
    }

    @Test
    fun `case11 - equal mtimes without timestamps - order unreliable even when sequential`() {
        val reports = tmp.resolve("reports-case11")
        Files.createDirectories(reports)
        val sameMtime = System.currentTimeMillis() - 60_000
        writeReport(
            reports,
            "TEST-a.A.xml",
            passingSuite("a.A", listOf("m")),
            sameMtime,
        )
        writeReport(
            reports,
            "TEST-b.B.xml",
            passingSuite("b.B", listOf("m")),
            sameMtime, // ровно то же mtime
        )

        val read = readReports(reports, ReadOptions(sequential = true))

        assertEquals(OrderSource.MTIME_HEURISTIC, read.orderSource, "timestamp-атрибутов нет — только mtime")
        assertTrue(
            read.orderUnreliable,
            "равные mtime → order_unreliable обязан быть в вердикте даже при --sequential",
        )
    }
}
