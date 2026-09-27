package io.github.wrinfotel.flakydiff.fixture

import io.github.wrinfotel.flakydiff.replay.defaultMvnCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Smoke генератора (план Task 4.1): fixture создаётся и `mvn -q test` в нём
 * проходит. Интеграционный — запускает реальный Maven.
 * Polluter стоит ПОСЛЕ жертвы: в записанном прогоне жертва чиста, всё зелёное.
 */
@Tag("integration")
class FixtureProjectSmokeTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `generated fixture passes mvn test`() {
        val fixture = generateFixture(
            tmp.resolve("fixture"),
            listOf(
                Scenario.SimplePassing("Alpha", 7),
                Scenario.SimplePassing("Beta", 7),
                Scenario.UnstableVictim("Unstable"),
                Scenario.Parameterized("Param"),
                Scenario.Victim("CleanVictim", "vc"),
                Scenario.PolluterPollutes("LatePolluter", "vc"),
            ),
        )

        val exit = runMvn(fixture.dir, "-q", "test")
        assertEquals(0, exit, "mvn -q test обязан пройти в сгенерированном fixture (см. вывод выше)")

        val reports = fixture.reportsDir()
        assertTrue(Files.isDirectory(reports), "surefire-reports обязаны существовать: $reports")
        val xmlReports = Files.list(reports).use { s ->
            s.filter { it.fileName.toString().endsWith(".xml") }.toList()
        }
        assertEquals(6, xmlReports.size, "по XML на тестовый класс: ${xmlReports.map { it.fileName }}")
    }

    private fun runMvn(dir: Path, vararg args: String): Int {
        val proc = ProcessBuilder(defaultMvnCommand + args.toList())
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        println("--- mvn ${args.toList()} in $dir ---\n$output--- end mvn ---")
        assertTrue(proc.waitFor(300, TimeUnit.SECONDS), "mvn завис; output:\n$output")
        return proc.exitValue()
    }
}
