package io.github.wrinfotel.flakydiff.cli

import io.github.wrinfotel.flakydiff.ddmin.Diagnosis
import io.github.wrinfotel.flakydiff.ddmin.FakeHarness
import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.replay.ReplayHarness
import io.github.wrinfotel.flakydiff.replay.ReplayResult
import io.github.wrinfotel.flakydiff.verdict.VerdictContext
import io.github.wrinfotel.flakydiff.verdict.VerdictType
import io.github.wrinfotel.flakydiff.verdict.buildVerdict
import io.github.wrinfotel.flakydiff.verdict.parseVerdictJson
import io.github.wrinfotel.flakydiff.verdict.renderJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Task 5.3 (план): CLI diagnose/replay/report. diagnose тестируется на unit-скорости:
 * fake-harness подменяется через subclass DiagnoseCommand (без реальных mvn/JVM),
 * XML — временные файлы. Эталоны: текст вердикта в stdout, JSON в --out.
 */
class FlakydiffCliTest {

    @TempDir
    lateinit var tmp: Path

    /** Запуск CLI с подменой DiagnoseCommand (фабрика picocli) и захватом stdout/stderr. */
    private class CapturedCli(private val diagnose: DiagnoseCommand? = null) {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        fun execute(vararg args: String): Int {
            val cmd = CommandLine(
                FlakydiffCli(),
                object : CommandLine.IFactory {
                    override fun <K : Any> create(cls: Class<K>): K {
                        if (cls == DiagnoseCommand::class.java && diagnose != null) {
                            @Suppress("UNCHECKED_CAST")
                            return diagnose as K
                        }
                        return CommandLine.defaultFactory().create(cls)
                    }
                },
            )
            cmd.setOut(PrintWriter(OutputStreamWriter(stdout, Charsets.UTF_8), true))
            cmd.setErr(PrintWriter(OutputStreamWriter(stderr, Charsets.UTF_8), true))
            return cmd.execute(*args)
        }
    }

    /** Подмена харнесса — единственная точка, где тест отсекает реальный prepareProject+JVM. */
    private class FakeDiagnoseCommand(private val harness: ReplayHarness) : DiagnoseCommand() {
        override fun buildHarness(durationsMs: Map<TestRef, Long>): ReplayHarness = harness
    }

    /** step0 проходит; любой зонд с prefix reproduces; confirmation 5/5 → ORDER_DEPENDENCY. */
    private fun odHarness(victim: TestRef): FakeHarness = FakeHarness { p, v, rep ->
        check(v == victim) { "unexpected victim $v" }
        when {
            rep == 5 -> ReplayResult.Reproduced(5, 5, TestFailure("java.lang.AssertionError", "boom", null))
            p.isEmpty() -> ReplayResult.NotReproduced(3, 3)
            else -> ReplayResult.Reproduced(rep, rep, TestFailure("java.lang.AssertionError", "boom", null))
        }
    }

    /** XML с точными suite-timestamp (урок case09: без них порядок — джиттер mtime). */
    private fun writeReport(dir: Path, name: String, cls: String, method: String, failed: Boolean, ts: String) {
        Files.createDirectories(dir)
        val failure = if (failed) "\n  <failure message=\"not clean\" type=\"org.opentest4j.AssertionFailedError\"/>" else ""
        Files.writeString(
            dir.resolve(name),
            """<testsuite name="$cls" time="0.1" tests="1" timestamp="$ts">
  <testcase name="$method" classname="$cls" time="0.1"/>$failure
</testsuite>""",
        )
    }

    private fun standardReports(): Path {
        val dir = tmp.resolve("reports")
        writeReport(dir, "TEST-com.example.ABallastTest.xml", "com.example.ABallastTest", "m1", false, "2026-01-01T00:00:01Z")
        writeReport(dir, "TEST-com.example.PolluterTest.xml", "com.example.PolluterTest", "poison", false, "2026-01-01T00:00:05Z")
        writeReport(dir, "TEST-com.example.VictimTest.xml", "com.example.VictimTest", "flaky", true, "2026-01-01T00:00:10Z")
        return dir
    }

    private fun diagnoseArgs(reports: Path, vararg extra: String): Array<String> {
        val base = mutableListOf(
            "diagnose",
            "--project", tmp.toString(),
            "--reports", reports.toString(),
            "--victim", "com.example.VictimTest#flaky",
        )
        base += extra
        return base.toTypedArray()
    }

    @Test
    fun `diagnose - fake harness - verdict text to stdout, json to out`() {
        val outDir = tmp.resolve("out")
        val cli = CapturedCli(FakeDiagnoseCommand(odHarness(TestRef("com.example.VictimTest", "flaky"))))

        val code = cli.execute(*diagnoseArgs(standardReports(), "--out", outDir.toString(), "--sequential"))

        assertEquals(0, code, cli.stderr.toString("UTF-8"))
        val text = cli.stdout.toString("UTF-8")
        assertTrue(text.contains("VERDICT: ORDER-DEPENDENCY"), text)
        assertTrue(text.contains("repro:    flakydiff replay --project"), text)
        val jsonFile = outDir.resolve("verdict.json")
        assertTrue(Files.exists(jsonFile), "JSON вердикта записан в --out")
        assertEquals(VerdictType.ORDER_DEPENDENCY, parseVerdictJson(Files.readString(jsonFile)).type)
    }

    @Test
    fun `diagnose - victim missing from reports - honest error exit 1`() {
        val cli = CapturedCli(FakeDiagnoseCommand(odHarness(TestRef("com.example.VictimTest", "flaky"))))

        val code = cli.execute(
            "diagnose", "--project", tmp.toString(), "--reports", standardReports().toString(),
            "--victim", "com.example.NopeTest#x",
        )

        assertEquals(1, code)
        assertTrue(cli.stderr.toString("UTF-8").contains("not found"), cli.stderr.toString("UTF-8"))
    }

    @Test
    fun `diagnose - max-classes caps prefix to N entries with warning`() {
        val dir = tmp.resolve("reports4")
        for (i in 1..6) {
            writeReport(dir, "TEST-c$i.xml", "com.example.A$i", "m1", false, "2026-01-01T00:00:0$i" + "Z")
        }
        writeReport(dir, "TEST-v.xml", "com.example.ZVictim", "flaky", true, "2026-01-01T00:00:20Z")
        val h = odHarness(TestRef("com.example.ZVictim", "flaky"))
        val cli = CapturedCli(FakeDiagnoseCommand(h))

        val code = cli.execute(
            "diagnose", "--project", tmp.toString(), "--reports", dir.toString(),
            "--victim", "com.example.ZVictim#flaky", "--max-classes", "2",
        )

        assertEquals(0, code, cli.stderr.toString("UTF-8"))
        // записанный prefix — 6 классов; кап 2 → ровно первые 2 записи:
        // «первая половина» (3) нарушала бы кап — митигация стоимости не работала
        val step1 = h.calls.first { it.prefix.isNotEmpty() }
        assertEquals(2, step1.prefix.size, "prefix после капа: ${step1.prefix}")
        assertTrue(cli.stdout.toString("UTF-8").contains("max-classes"), "предупреждение о сужении")
    }

    @Test
    fun `diagnose - prefix-file overrides recorded prefix`() {
        val pf = tmp.resolve("prefix.txt")
        Files.writeString(pf, "com.example.PolluterTest#poison\n")
        val h = odHarness(TestRef("com.example.VictimTest", "flaky"))
        val cli = CapturedCli(FakeDiagnoseCommand(h))

        val code = cli.execute(*diagnoseArgs(standardReports(), "--prefix-file", pf.toString()))

        assertEquals(0, code, cli.stderr.toString("UTF-8"))
        // записанный prefix — 2 класса (ABallast, Polluter); файл сужает ровно до одного
        assertEquals(
            listOf(TestRef("com.example.PolluterTest", "poison")),
            h.calls.first { it.prefix.isNotEmpty() }.prefix,
        )
    }

    @Test
    fun `diagnose - help mentions cost mitigations`() {
        val cli = CapturedCli()

        val code = cli.execute("diagnose", "--help")

        assertEquals(0, code)
        val text = cli.stdout.toString("UTF-8")
        assertTrue(text.contains("--prefix-file"), text)
        assertTrue(text.contains("--max-classes"), text)
        assertTrue(text.contains("--min-fail-ratio"), text)
        assertTrue(text.contains("свежая JVM"), "митигация стоимости в описании: $text")
    }

    @Test
    fun `replay - missing required options - usage error exit 2`() {
        val cli = CapturedCli()

        assertEquals(2, cli.execute("replay"))
    }

    @Test
    fun `diagnose - reports dir missing - honest usage error, no stack trace`() {
        val cli = CapturedCli(FakeDiagnoseCommand(odHarness(TestRef("com.example.VictimTest", "flaky"))))

        val code = cli.execute(
            "diagnose", "--project", tmp.toString(), "--reports", tmp.resolve("nope").toString(),
            "--victim", "com.example.VictimTest#flaky",
        )

        assertEquals(2, code)
        assertTrue(
            cli.stderr.toString("UTF-8").contains("reports directory not found"),
            cli.stderr.toString("UTF-8"),
        )
    }

    @Test
    @org.junit.jupiter.api.Tag("integration")
    fun `replay - project without pom - honest error exit 2`() {
        val cli = CapturedCli()

        val code = cli.execute(
            "replay", "--project", tmp.toString(),
            "--victim", "com.example.VictimTest#flaky",
        )

        assertEquals(2, code)
        assertTrue(
            cli.stderr.toString("UTF-8").contains("replay failed"),
            cli.stderr.toString("UTF-8"),
        )
    }

    private fun verdictFile(): Path {
        val victim = TestRef("com.example.VictimTest", "flaky")
        val v = buildVerdict(
            Diagnosis.OrderDependency(
                victim, listOf(TestRef("com.example.PolluterTest", "poison")), null,
                3, 3, 5, 5,
            ),
            VerdictContext(projectDir = "path/to/project", repeat = 3, orderUnreliable = false),
        )
        val f = tmp.resolve("verdict.json")
        Files.writeString(f, renderJson(v))
        return f
    }

    @Test
    fun `report - renders text verdict from json file`() {
        val cli = CapturedCli()

        val code = cli.execute("report", "--verdict", verdictFile().toString())

        assertEquals(0, code, cli.stderr.toString("UTF-8"))
        assertTrue(cli.stdout.toString("UTF-8").contains("VERDICT: ORDER-DEPENDENCY"))
    }

    @Test
    fun `report - json format prints json`() {
        val cli = CapturedCli()

        val code = cli.execute("report", "--verdict", verdictFile().toString(), "--format", "json")

        assertEquals(0, code, cli.stderr.toString("UTF-8"))
        val text = cli.stdout.toString("UTF-8")
        assertTrue(text.contains("\"type\"") && text.contains("ORDER_DEPENDENCY"), text)
    }

    @Test
    fun `report - missing file - exit 2`() {
        val cli = CapturedCli()

        assertEquals(2, cli.execute("report", "--verdict", tmp.resolve("nope.json").toString()))
    }
}
