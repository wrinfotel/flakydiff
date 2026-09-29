package io.github.wrinfotel.flakydiff.cli

import io.github.wrinfotel.flakydiff.fixture.Scenario
import io.github.wrinfotel.flakydiff.fixture.generateFixture
import io.github.wrinfotel.flakydiff.replay.defaultMvnCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * DoD-п.2 (план): CLI-smoke на fixture — diagnose по реальному fixture-проекту
 * (mvn-зонды, БЕЗ подмены харнесса), replay даёт Reproduced на fixture-polluter,
 * report читает JSON вердикта и рендерит текст.
 *
 * Регрессия (найдена DoD-smoke): относительный --project ломал prepareProject —
 * mvn пишет classpath-файл относительно СВОЕЙ cwd, а код искал его относительно
 * cwd CLI. Проект обязан нормализоваться в абсолютный путь до prepareProject.
 */
@Tag("integration")
class DiagnoseCliE2eTest {

    private val stdout = ByteArrayOutputStream()
    private val stderr = ByteArrayOutputStream()

    private fun execute(vararg args: String): Int {
        stdout.reset()
        stderr.reset()
        val cmd = CommandLine(FlakydiffCli())
        cmd.setOut(PrintWriter(OutputStreamWriter(stdout, Charsets.UTF_8), true))
        cmd.setErr(PrintWriter(OutputStreamWriter(stderr, Charsets.UTF_8), true))
        return cmd.execute(*args)
    }

    private fun runMvnIn(dir: Path, vararg args: String): Int {
        val proc = ProcessBuilder(defaultMvnCommand + args.toList())
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(600, TimeUnit.SECONDS), "mvn завис; output:\n$output")
        return proc.exitValue()
    }

    @Test
    fun `cli smoke on fixture - diagnose, replay, report with relative project path`() {
        val fixtureDir = Path.of("target", "cli-e2e-fixture")
        Files.createDirectories(fixtureDir)
        val fixture = generateFixture(
            fixtureDir,
            listOf(
                Scenario.SimplePassing("Ballast01", 5),
                Scenario.PolluterPollutes("Polluter", "v1"),
                Scenario.Victim("Victim", "v1"),
            ),
        )
        assertTrue(runMvnIn(fixture.dir, "-B", "test") != 0, "recorded прогон обязан упасть: жертва загрязнена")

        val victim = "io.github.wrinfotel.fixture.T03_VictimTest#shouldWork"
        val outDir = fixtureDir.resolve("verdict-out")

        // 1) diagnose: относительный --project, реальные mvn-зонды в свежих JVM
        val code = execute(
            "diagnose",
            "--project", fixtureDir.toString(),
            "--reports", fixture.reportsDir().toString(),
            "--victim", victim,
            "--out", outDir.toString(),
            "--sequential",
        )
        assertEquals(0, code, stderr.toString("UTF-8"))
        val verdictText = stdout.toString("UTF-8")
        assertTrue(verdictText.contains("VERDICT: ORDER-DEPENDENCY"), verdictText)
        assertTrue(verdictText.contains("polluter: io.github.wrinfotel.fixture.T02_PolluterTest#pollutes"), verdictText)

        // 2) report: JSON вердикта читается обратно и рендерится в текст
        val json = outDir.resolve("verdict.json")
        assertTrue(Files.exists(json), "JSON вердикта записан в --out")
        val reportCode = execute("report", "--verdict", json.toString())
        assertEquals(0, reportCode, stderr.toString("UTF-8"))
        assertTrue(stdout.toString("UTF-8").contains("VERDICT: ORDER-DEPENDENCY"))

        // 3) replay: polluter-запись БЕЗ #method (форма repro-команды) — Reproduced
        val replayCode = execute(
            "replay",
            "--project", fixtureDir.toString(),
            "--prefix", "io.github.wrinfotel.fixture.T02_PolluterTest",
            "--victim", victim,
        )
        assertEquals(0, replayCode, stderr.toString("UTF-8"))
        assertTrue(stdout.toString("UTF-8").contains("REPRODUCED"), stdout.toString("UTF-8"))
    }
}
