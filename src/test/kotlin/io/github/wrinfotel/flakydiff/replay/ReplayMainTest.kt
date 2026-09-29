package io.github.wrinfotel.flakydiff.replay

import io.github.wrinfotel.flakydiff.reader.TestRun
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.commons.JUnitException
import org.junit.platform.engine.TestEngine
import org.junit.platform.launcher.core.LauncherFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

/**
 * ReplayMain тестируется через ProcessBuilder: реальная свежая JVM, classpath
 * проекта ПЕРВЫМ (target/test-classes, target/classes), сторонние jars после —
 * правило classpath из шапки плана. Состав classpath собирается по codeSource
 * загруженных классов — надёжно и под surefire (manifest-only booter jar).
 * Integration: каждый тест порождает реальные JVM-зонды (правило Task 4.1 плана).
 */
@Tag("integration")
class ReplayMainTest {

    @TempDir
    lateinit var tmp: Path

    private fun javaExe(): String {
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", exe).toString()
    }

    private fun entryOf(cls: Class<*>): String =
        Path.of(cls.protectionDomain.codeSource.location.toURI()).toString()

    private fun childClasspath(): String =
        listOf(
            ReplayMainTest::class.java,   // target/test-classes (FakePassing, FakeFailing, ...)
            TestRun::class.java,          // target/classes (main-код flakydiff)
            LauncherFactory::class.java,  // junit-platform-launcher
            JupiterTestEngine::class.java,// junit-jupiter-engine
            org.junit.jupiter.params.ParameterizedTest::class.java, // junit-jupiter-params
            org.junit.jupiter.api.Test::class.java, // junit-jupiter-api
            org.opentest4j.AssertionFailedError::class.java, // opentest4j
            TestEngine::class.java,       // junit-platform-engine
            JUnitException::class.java,   // junit-platform-commons
            kotlin.Unit::class.java,      // kotlin-stdlib
        ).map(::entryOf).distinct().joinToString(File.pathSeparator)

    private fun runReplay(report: Path, vararg args: String, jvmArgs: List<String> = emptyList()): Pair<Process, String> {
        val cmd = mutableListOf(javaExe())
        cmd += jvmArgs
        cmd += listOf(
            "-cp",
            childClasspath(),
            "io.github.wrinfotel.flakydiff.replay.ReplayMain",
        )
        args.forEach { cmd.add(it) }
        cmd += listOf("--report", report.toString())
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "probe JVM hung; output:\n$output")
        return proc to output
    }

    private fun readEntries(report: Path): List<kotlinx.serialization.json.JsonObject> {
        val json = Json.parseToJsonElement(report.readText()).jsonObject
        assertEquals(true, json["jvmStarted"]?.jsonPrimitive?.boolean, "jvmStarted must be true")
        return json["results"]!!.jsonArray.map { it.jsonObject }
    }

    private fun entry(
        cls: String,
        method: String,
        status: String,
        failureType: String?,
        stage: String,
    ): kotlinx.serialization.json.JsonObject {
        val actual = kotlinx.serialization.json.buildJsonObject {
            put("class", kotlinx.serialization.json.JsonPrimitive(cls))
            put("method", kotlinx.serialization.json.JsonPrimitive(method))
            put("status", kotlinx.serialization.json.JsonPrimitive(status))
            if (failureType != null) {
                put("failureType", kotlinx.serialization.json.JsonPrimitive(failureType))
            }
            put("stage", kotlinx.serialization.json.JsonPrimitive(stage))
        }
        // failureMessage у части кейсов есть, у части null — сравниваем только набор ключей + поля выше
        return actual
    }

    private fun assertEntry(actual: kotlinx.serialization.json.JsonObject, expected: kotlinx.serialization.json.JsonObject) {
        for ((key, value) in expected) {
            assertEquals(value, actual[key], "mismatch on '$key' in $actual")
        }
    }

    @Test
    fun `victim repeats three times with stage TEST and failure type`() {
        val report = tmp.resolve("report.json")
        val (proc, output) = runReplay(
            report,
            "--victim", "io.github.wrinfotel.flakydiff.replay.FakeFailing#alwaysFails",
            "--repeat", "3",
        )
        assertEquals(0, proc.exitValue(), "exit code must be 0 even with FAILED victim; output:\n$output")

        val entries = readEntries(report)
        assertEquals(3, entries.size, "report:\n${report.readText()}")
        entries.forEach {
            assertEntry(
                it,
                entry(
                    "io.github.wrinfotel.flakydiff.replay.FakeFailing",
                    "alwaysFails",
                    "FAILED",
                    "java.lang.AssertionError",
                    "TEST",
                ),
            )
        }
        assertEquals("boom", entries[0]["failureMessage"]?.jsonPrimitive?.content)
    }

    @Test
    fun `prefix runs once before victim repeats`() {
        val report = tmp.resolve("report.json")
        val (proc, output) = runReplay(
            report,
            "--prefix", "io.github.wrinfotel.flakydiff.replay.FakePassing#alwaysPasses",
            "--victim", "io.github.wrinfotel.flakydiff.replay.FakeFailing#alwaysFails",
        )
        assertEquals(0, proc.exitValue(), "output:\n$output")

        val entries = readEntries(report)
        assertEquals(4, entries.size, "report:\n${report.readText()}") // 1 prefix + repeat по умолчанию 3
        assertEntry(
            entries[0],
            entry(
                "io.github.wrinfotel.flakydiff.replay.FakePassing",
                "alwaysPasses",
                "PASSED",
                null,
                "TEST",
            ),
        )
        entries.drop(1).forEach {
            assertEquals(
                "io.github.wrinfotel.flakydiff.replay.FakeFailing",
                it["class"]?.jsonPrimitive?.content,
            )
            assertEquals("FAILED", it["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `beforeEach failure reported with stage BEFORE_EACH`() {
        val report = tmp.resolve("report.json")
        val (proc, output) = runReplay(
            report,
            "--victim", "io.github.wrinfotel.flakydiff.replay.FakeBeforeEachFails#neverRuns",
        )
        assertEquals(0, proc.exitValue(), "output:\n$output")

        val entries = readEntries(report)
        assertEquals(3, entries.size, "report:\n${report.readText()}")
        entries.forEach {
            assertEntry(
                it,
                entry(
                    "io.github.wrinfotel.flakydiff.replay.FakeBeforeEachFails",
                    "neverRuns",
                    "FAILED",
                    "java.lang.IllegalStateException",
                    "BEFORE_EACH",
                ),
            )
        }
    }

    @Test
    fun `whole-class prefix element runs all methods in discovery order`() {
        val report = tmp.resolve("report.json")
        val (proc, output) = runReplay(
            report,
            "--prefix", "io.github.wrinfotel.flakydiff.replay.FakeTwoMethods",
            "--victim", "io.github.wrinfotel.flakydiff.replay.FakeFailing#alwaysFails",
        )
        assertEquals(0, proc.exitValue(), "output:\n$output")

        val entries = readEntries(report)
        assertEquals(5, entries.size, "report:\n${report.readText()}") // 2 метода класса + 3 повтора жертвы
        val prefixMethods = entries.take(2).map {
            it["method"]?.jsonPrimitive?.content
        }.toSet()
        assertEquals(setOf("first", "second"), prefixMethods, "report:\n${report.readText()}")
        entries.take(2).forEach { assertEquals("PASSED", it["status"]?.jsonPrimitive?.content) }
        entries.drop(2).forEach {
            assertEquals("FAILED", it["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `victim-timeout watchdog aborts hanging repeat with infra marker`() {
        val report = tmp.resolve("report.json")
        val startedAt = System.nanoTime()
        val (proc, output) = runReplay(
            report,
            "--victim", "io.github.wrinfotel.flakydiff.replay.FakeHangingVictim#hangs",
            "--victim-timeout", "1",
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(0, proc.exitValue(), "output:\n$output")
        assertTrue(elapsedMs < 15_000, "watchdog обязан оборвать зависший повтор быстро, elapsed=${elapsedMs}ms")

        val entries = readEntries(report)
        // первый же зависший повтор обрывает остальные (timeout -> infra, повторять бессмысленно)
        assertEquals(1, entries.size, "report:\n${report.readText()}")
        assertEquals(
            "flakydiff.victim-timeout",
            entries[0]["failureType"]?.jsonPrimitive?.content,
            "report:\n${report.readText()}",
        )
        assertEquals("FAILED", entries[0]["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `repeat below 3 is rejected`() {
        val report = tmp.resolve("report.json")
        val (proc, _) = runReplay(
            report,
            "--victim", "io.github.wrinfotel.flakydiff.replay.FakeFailing#alwaysFails",
            "--repeat", "2",
        )
        assertTrue(proc.exitValue() != 0, "N<3 обязан быть отклонён ненулевым кодом выхода")
        assertTrue(!Files.exists(report), "отчёт при ошибке аргументов не пишется")
    }

    @Test
    fun `display-name prefix entry falls back to base method and runs all invocations`() {
        val report = tmp.resolve("report.json")
        val (proc, output) = runReplay(
            report,
            "--prefix", "io.github.wrinfotel.flakydiff.replay.FakeParamMethod#check(int)[2]",
            "--victim", "io.github.wrinfotel.flakydiff.replay.FakeFailing#alwaysFails",
        )
        assertEquals(0, proc.exitValue(), "output:\n$output")

        val entries = readEntries(report)
        val paramRows = entries.filter {
            it["class"]?.jsonPrimitive?.content == "io.github.wrinfotel.flakydiff.replay.FakeParamMethod"
        }
        assertEquals(3, paramRows.size, "fallback обязан прогнать все invocations:\n${report.readText()}")
        assertEquals(setOf("check"), paramRows.map { it["method"]?.jsonPrimitive?.content }.toSet())
        paramRows.forEach { assertEquals("PASSED", it["status"]?.jsonPrimitive?.content) }
        assertTrue(
            entries.none { it["failureType"]?.jsonPrimitive?.content == "flakydiff.prefix-unresolved" },
            "адресованная фолбэком ссылка не помечается: ${report.readText()}",
        )
        assertEquals(
            3,
            entries.count { it["class"]?.jsonPrimitive?.content == "io.github.wrinfotel.flakydiff.replay.FakeFailing" },
            "жертва повторяется как обычно:\n${report.readText()}",
        )
    }

    @Test
    fun `unresolvable prefix entry is reported as prefix-unresolved, not silently dropped`() {
        val report = tmp.resolve("report.json")
        val (proc, output) = runReplay(
            report,
            "--prefix", "io.github.wrinfotel.flakydiff.replay.FakePassing#noSuchMethod",
            "--victim", "io.github.wrinfotel.flakydiff.replay.FakeFailing#alwaysFails",
        )
        assertEquals(0, proc.exitValue(), "output:\n$output")

        val entries = readEntries(report)
        val unresolved = entries.filter {
            it["failureType"]?.jsonPrimitive?.content == "flakydiff.prefix-unresolved"
        }
        assertEquals(1, unresolved.size, "report:\n${report.readText()}")
        val row = unresolved[0]
        assertEquals("io.github.wrinfotel.flakydiff.replay.FakePassing", row["class"]?.jsonPrimitive?.content)
        assertEquals("noSuchMethod", row["method"]?.jsonPrimitive?.content)
        assertEquals("FAILED", row["status"]?.jsonPrimitive?.content)
        assertEquals("ENGINE", row["stage"]?.jsonPrimitive?.content)
        assertTrue(
            (row["failureMessage"]?.jsonPrimitive?.content ?: "").contains("not addressable"),
            "диагностика с причиной: ${report.readText()}",
        )
    }

    @Test
    fun `recorded prefix order wins over engine class reordering`() {
        // ClassOrderer.ClassName сортирует классы алфавитно (Cleaner < Polluter) —
        // обратный к записанному порядок. Зонд обязан исполнить Polluter, затем
        // Cleaner: иначе cleaner не снимет загрязнение и жертва упадёт.
        val report = tmp.resolve("report.json")
        val (proc, output) = runReplay(
            report,
            "--prefix", "io.github.wrinfotel.flakydiff.replay.FakeOrderPolluter," +
                "io.github.wrinfotel.flakydiff.replay.FakeOrderCleaner",
            "--victim", "io.github.wrinfotel.flakydiff.replay.FakeStatefulVictim#failsWhenDirty",
            jvmArgs = listOf("-Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer\$ClassName"),
        )
        assertEquals(0, proc.exitValue(), "output:\n$output")

        val entries = readEntries(report)
        val victimRows = entries.filter {
            it["class"]?.jsonPrimitive?.content == "io.github.wrinfotel.flakydiff.replay.FakeStatefulVictim"
        }
        assertEquals(3, victimRows.size, "report:\n${report.readText()}")
        victimRows.forEach {
            assertEquals(
                "PASSED",
                it["status"]?.jsonPrimitive?.content,
                "порядок записанного prefix нарушен — cleaner обязан был исполниться последним:\n${report.readText()}",
            )
        }
    }
}
