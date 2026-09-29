package io.github.wrinfotel.flakydiff.replay

import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.reader.TestRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

interface ReplayHarness {
    /** Каждый вызов = новый процесс (свежая JVM): prefix → victim, repeat повторов жертвы. */
    fun replay(prefix: List<TestRef>, victim: TestRef, repeat: Int = 3): ReplayResult
}

sealed interface ReplayResult {
    /** Жертва упала ≥2 из repeat. */
    data class Reproduced(val failures: Int, val attempts: Int, val failure: TestFailure) : ReplayResult

    /** Включает 3/3 и 1/3: слабый сигнал консервативно не воспроизведён. */
    data class NotReproduced(val passes: Int, val attempts: Int) : ReplayResult

    /** Компиляция/classpath/JVM/таймаут — НЕ фейл теста (спека §4.2). */
    data class InfraError(val cause: String) : ReplayResult
}

private data class ProbeRow(
    val cls: String,
    val method: String,
    val status: String,
    val failureType: String?,
    val failureMessage: String?,
    val stage: String,
)

/**
 * Свежая JVM на зонд. Classpath проекта ПЕРВЫМ, probeClasspath (в реальном
 * использовании — flakydiff-replay.jar) ПОСЛЕДНИМ: Launcher/движок берутся
 * версии проекта пользователя, shade добирает только отсутствующее.
 *
 * @param durationsMs длительности тестов из XML-прогонов — для честных таймаутов
 *   (victimTimeout = clamp(duration×5, 30s, 180s), probeTimeout по формуле плана).
 * @param victimTimeoutMs явное перекрытие clamp'а; @param probeTimeoutMs — рамка
 *   на весь процесс зонда (kill по истечении).
 */
class JvmReplayHarness(
    private val project: MavenProjectInfo,
    private val probeClasspath: List<Path>,
    private val durationsMs: Map<TestRef, Long> = emptyMap(),
    private val victimTimeoutMs: Long? = null,
    private val probeTimeoutMs: Long? = null,
) : ReplayHarness {

    override fun replay(prefix: List<TestRef>, victim: TestRef, repeat: Int): ReplayResult {
        val report = Files.createTempFile("flakydiff-probe-", ".json")
        try {
            val cmd = buildCommand(prefix, victim, repeat, report)
            val procBuilder = ProcessBuilder(cmd)
                .directory(project.projectDir.toFile())
                .redirectErrorStream(true)
            procBuilder.environment().putAll(probeEnvironment())
            val proc = procBuilder.start()
            val output = arrayOfNulls<String>(1)
            val drain = Thread {
                output[0] = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            }.apply {
                isDaemon = true
                start()
            }

            val timeoutMs = probeTimeoutMs ?: defaultProbeTimeoutMs(prefix, victim, repeat)
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                drain.join(2_000)
                return ReplayResult.InfraError("probe timeout after ${timeoutMs}ms")
            }
            drain.join(2_000)
            if (proc.exitValue() != 0) {
                return ReplayResult.InfraError(
                    "ReplayMain exited with ${proc.exitValue()}: ${output[0]?.takeLast(2000) ?: ""}",
                )
            }
            if (!Files.exists(report)) {
                return ReplayResult.InfraError("ReplayMain wrote no report")
            }

            val rows = parseReport(report)
            // Инфра-фейл ЛЮБОЙ строки (prefix или жертва) ломает зонд целиком:
            // честный InfraError, не фейл теста — иначе ddmin сузит по мусору.
            rows.firstOrNull {
                it.status == "FAILED" &&
                    FailureClassifier.classify(it.stage, it.failureType) == FailureKind.INFRA_ERROR
            }?.let {
                return ReplayResult.InfraError(
                    "infra failure at ${it.cls}#${it.method}: stage=${it.stage}, " +
                        "type=${it.failureType}, msg=${it.failureMessage ?: ""}",
                )
            }

            val victimRows = rows.filter { it.cls == victim.testClass && it.method == victim.method }
            if (victimRows.size < repeat) {
                return ReplayResult.InfraError("victim ran ${victimRows.size} of $repeat repeats")
            }
            val attempts = victimRows.takeLast(repeat)
            val failures = attempts.count { it.status == "FAILED" }
            if (failures >= 2) {
                val first = attempts.first { it.status == "FAILED" }
                return ReplayResult.Reproduced(
                    failures,
                    repeat,
                    TestFailure(first.failureType, first.failureMessage, null),
                )
            }
            val passes = attempts.count { it.status == "PASSED" }
            return ReplayResult.NotReproduced(passes, repeat)
        } finally {
            Files.deleteIfExists(report)
        }
    }

    /**
     * Команда зонда: свойства/argLine из effective-pom, cp = target/test-classes +
     * target/classes + зависимости + probeClasspath последним, затем ReplayMain
     * с именованными аргументами. Публичная — контракт собираемой команды.
     */
    fun buildCommand(prefix: List<TestRef>, victim: TestRef, repeat: Int, report: Path): List<String> {
        val cp = (
            listOf(project.testClassesDir, project.classesDir) +
                project.classpathEntries +
                probeClasspath
            ).joinToString(File.pathSeparator) { it.toString() }

        val cmd = mutableListOf<String>()
        cmd.add(javaExecutable())
        project.systemProperties.forEach { (k, v) -> cmd.add("-D$k=$v") }
        splitArgLine(project.argLine ?: "").forEach(cmd::add)
        cmd.add("-cp")
        cmd.add(cp)
        cmd.add("io.github.wrinfotel.flakydiff.replay.ReplayMain")
        cmd.add("--prefix")
        // Ссылка без метода = весь класс (форма repro-команды вердикта, контракт Task 2.1)
        cmd.add(
            prefix.joinToString(",") { ref ->
                if (ref.method.isEmpty()) ref.testClass else "${ref.testClass}#${ref.method}"
            },
        )
        cmd.add("--victim")
        cmd.add("${victim.testClass}#${victim.method}")
        cmd.add("--repeat")
        cmd.add(repeat.toString())
        cmd.add("--report")
        cmd.add(report.toString())
        cmd.add("--victim-timeout")
        cmd.add(victimTimeoutSec(victim).toString())
        return cmd
    }

    /**
     * Активированные (activeByDefault) профили → env зонда (спека §4.2 «activated
     * profiles → env»). Эффекты профилей на pom уже интерполированы в effective-pom —
     * это канал для тестов, ветвящихся на окружение прогона.
     */
    internal fun probeEnvironment(): Map<String, String> =
        if (project.profiles.isEmpty()) {
            emptyMap()
        } else {
            mapOf("MAVEN_ACTIVE_PROFILES" to project.profiles.joinToString(","))
        }

    private fun victimTimeoutSec(victim: TestRef): Long =
        ((victimTimeoutMs ?: clampVictimTimeoutMs(durationsMs[victim] ?: 0L)) + 999) / 1000

    private fun defaultProbeTimeoutMs(prefix: List<TestRef>, victim: TestRef, repeat: Int): Long {
        val prefixSum = prefix.sumOf { durationsMs[it] ?: 0L }
        val vt = victimTimeoutMs ?: clampVictimTimeoutMs(durationsMs[victim] ?: 0L)
        return (prefixSum * 2 + vt * repeat + 60_000).coerceAtMost(900_000)
    }

    private fun javaExecutable(): String {
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", exe).toString()
    }

    private fun parseReport(report: Path): List<ProbeRow> {
        val root = Json.parseToJsonElement(Files.readString(report)).jsonObject
        val results = root["results"]?.jsonArray ?: return emptyList()
        return results.map { el ->
            val o = el.jsonObject
            ProbeRow(
                cls = o["class"]?.jsonPrimitive?.content ?: "",
                method = o["method"]?.jsonPrimitive?.content ?: "",
                status = o["status"]?.jsonPrimitive?.content ?: "",
                failureType = o["failureType"]?.jsonPrimitive?.contentOrNull,
                failureMessage = o["failureMessage"]?.jsonPrimitive?.contentOrNull,
                stage = o["stage"]?.jsonPrimitive?.content ?: "",
            )
        }
    }
}

/** Пол clamp-рамки повтора жертвы; он же дефолт replay без XML-длительностей. */
internal const val VICTIM_TIMEOUT_FLOOR_SEC = 30L

/** clamp(duration×5, 30s, 180s) — честная рамка, без псевдостатистики (спека §4.2). */
internal fun clampVictimTimeoutMs(durationMs: Long): Long =
    (durationMs * 5).coerceIn(VICTIM_TIMEOUT_FLOOR_SEC * 1_000, 180_000)

/** Итоговая рамка повтора жертвы в секундах: явный override важнее clamp'а длительности. */
internal fun effectiveVictimTimeoutSec(durationMs: Long?, overrideSec: Long?): Long =
    ((overrideSec?.times(1_000) ?: clampVictimTimeoutMs(durationMs ?: 0L)) + 999) / 1_000

/**
 * argLine — строка аргументов JVM, а не shell-команда: ProcessBuilder передаёт токены
 * напрямую, без шелл-разбора. Двойные кавычки группируют пробелы внутри одного
 * аргумента (javaagent с путём «C:\Program Files\...»), `\"` — литеральная кавычка.
 */
internal fun splitArgLine(argLine: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var hasToken = false
    var i = 0
    while (i < argLine.length) {
        val c = argLine[i]
        when {
            c == '\\' && i + 1 < argLine.length && argLine[i + 1] == '"' -> {
                current.append('"')
                hasToken = true
                i += 2
                continue
            }
            c == '"' -> inQuotes = !inQuotes
            !inQuotes && c.isWhitespace() -> {
                if (hasToken) tokens.add(current.toString())
                current.setLength(0)
                hasToken = false
            }
            else -> {
                current.append(c)
                hasToken = true
            }
        }
        i++
    }
    if (hasToken) tokens.add(current.toString())
    return tokens
}
