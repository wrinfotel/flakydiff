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
 * DoD item 2 (plan): CLI smoke on a fixture — diagnose against a real fixture project
 * (mvn probes, NO harness substitution), replay returns Reproduced on the fixture polluter,
 * report reads the verdict JSON and renders the text.
 *
 * Regression (found by the DoD smoke): a relative --project broke prepareProject —
 * mvn writes the classpath file relative to ITS OWN cwd, while the code looked for it relative
 * to the CLI's cwd. The project must be normalized to an absolute path before prepareProject.
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

        // 1) diagnose: relative --project, real mvn probes in fresh JVMs
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

        // 2) report: the verdict JSON is read back and rendered as text
        val json = outDir.resolve("verdict.json")
        assertTrue(Files.exists(json), "JSON вердикта записан в --out")
        val reportCode = execute("report", "--verdict", json.toString())
        assertEquals(0, reportCode, stderr.toString("UTF-8"))
        assertTrue(stdout.toString("UTF-8").contains("VERDICT: ORDER-DEPENDENCY"))

        // 3) replay: polluter entry WITHOUT #method (the repro-command form) — Reproduced
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
