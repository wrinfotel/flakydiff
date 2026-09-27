package io.github.wrinfotel.flakydiff.replay

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.core.LauncherFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Зонд (спека §4.2): запускает prefix → жертва×repeat в ОДНОЙ JVM. Свежая JVM
 * на весь зонд гарантируется харнессом (Task 2.5), состояние между повторами
 * жертвы не сбрасывается. Результат — JSON в --report, не в stdout.
 *
 * Сериализация JSON ручная: в replay-jar (Task 2.3) не пакуются ни
 * kotlinx-serialization, ни picocli — поэтому и парсинг аргументов, и JSON
 * здесь без зависимостей.
 */
object ReplayMain {

    private const val USAGE =
        "usage: ReplayMain --prefix <FQCN#method|FQCN,...> --victim <FQCN#method> " +
            "[--repeat <N>=3] --report <path.json> [--probe-timeout <sec>] [--victim-timeout <sec>]"

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args) { System.err.println(it) })
    }

    internal fun run(args: Array<String>, err: (String) -> Unit): Int {
        val opts = parseArgs(args, err) ?: return 2
        val entries = mutableListOf<Entry>()
        val listener = CollectingListener(entries)
        val launcher = LauncherFactory.create()

        try {
            if (opts.prefix.isNotEmpty()) {
                val selectors = opts.prefix.map {
                    if (it.method == null) DiscoverySelectors.selectClass(it.cls)
                    else DiscoverySelectors.selectMethod(it.cls, it.method)
                }
                launcher.execute(
                    org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request()
                        .selectors(selectors)
                        .build(),
                    listener,
                )
            }
            val victimSelector = DiscoverySelectors.selectMethod(opts.victim.cls, opts.victim.method)
            // Повторы жертвы — отдельные discovery-запросы в ТОЙ ЖЕ JVM: платформа
            // дедуплицирует одинаковые селекторы внутри одного запроса, а состояние
            // между execute() не сбрасывается — семантика загрязнения сохраняется.
            repeat(opts.repeat) {
                launcher.execute(
                    org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request()
                        .selectors(listOf(victimSelector))
                        .build(),
                    listener,
                )
            }
        } catch (t: Throwable) {
            // Ошибка на уровне движка/Launcher — честно попадает в отчёт (stage=ENGINE),
            // а не молча в exit-код.
            entries.add(Entry("", "", "FAILED", t.javaClass.name, t.message, "ENGINE"))
        }

        Files.writeString(Path.of(opts.report), buildJson(entries))
        return 0
    }

    internal data class PrefixRef(val cls: String, val method: String?)

    internal data class Options(
        val prefix: List<PrefixRef>,
        val victim: PrefixRef,
        val repeat: Int,
        val report: String,
        val probeTimeoutSec: Long?,
        val victimTimeoutSec: Long?,
    )

    private fun parseArgs(args: Array<String>, err: (String) -> Unit): Options? {
        val values = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val flag = args[i]
            if (!flag.startsWith("--") || i + 1 >= args.size) {
                err("flakydiff replay: unexpected argument '$flag'\n$USAGE")
                return null
            }
            values[flag.substring(2)] = args[i + 1]
            i += 2
        }

        val report = values["report"]
        if (report.isNullOrBlank()) {
            err("flakydiff replay: --report is required\n$USAGE")
            return null
        }
        val victim = values["victim"]?.let { parseRef(it) }
        if (victim == null || victim.method == null) {
            err("flakydiff replay: --victim must be FQCN#method\n$USAGE")
            return null
        }
        val repeat = when (val raw = values["repeat"] ?: "3") {
            else -> raw.toIntOrNull()
        }
        if (repeat == null || repeat < 3) {
            // N=1 — бакеты WEAK/FAILS не определены; N=2 — «≥2 из 2» = детерминизм,
            // не флейк-режим. Спека: N>=3 обязателен.
            err("flakydiff replay: --repeat must be an integer >= 3 (got '${values["repeat"]}')")
            return null
        }
        val probeTimeoutSec = values["probe-timeout"]?.let {
            val v = it.toLongOrNull()
            if (v == null || v <= 0) {
                err("flakydiff replay: --probe-timeout must be a positive integer (seconds)")
                return null
            }
            v
        }
        val victimTimeoutSec = values["victim-timeout"]?.let {
            val v = it.toLongOrNull()
            if (v == null || v <= 0) {
                err("flakydiff replay: --victim-timeout must be a positive integer (seconds)")
                return null
            }
            v
        }
        val prefix = mutableListOf<PrefixRef>()
        values["prefix"]?.let { raw ->
            if (raw.isNotBlank()) {
                for (part in raw.split(',')) {
                    val ref = parseRef(part) ?: continue
                    prefix.add(ref)
                }
            }
        }
        return Options(prefix, victim, repeat, report, probeTimeoutSec, victimTimeoutSec)
    }

    private fun parseRef(raw: String): PrefixRef? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val hash = s.indexOf('#')
        return if (hash < 0) PrefixRef(s, null)
        else PrefixRef(s.substring(0, hash), s.substring(hash + 1).ifBlank { null })
    }

    internal data class Entry(
        val cls: String,
        val method: String,
        val status: String,
        val failureType: String?,
        val failureMessage: String?,
        val stage: String,
    )

    private class CollectingListener(private val out: MutableList<Entry>) : TestExecutionListener {
        override fun executionFinished(identifier: TestIdentifier, result: TestExecutionResult) {
            if (!identifier.parentId.isPresent) return
            if (identifier.isContainer) {
                // Успешные контейнеры (классы, движок) в отчёт не попадают; падение
                // контейнера = ошибка на уровне класса/движка: @BeforeAll → BEFORE_ALL,
                // движок/Launcher → ENGINE.
                if (result.status != TestExecutionResult.Status.FAILED) return
                val t = result.throwable.orElse(null)
                when (val source = identifier.source.orElse(null)) {
                    is ClassSource -> out.add(
                        Entry(source.className, "", "FAILED", t?.javaClass?.name, t?.message, "BEFORE_ALL"),
                    )
                    else -> out.add(
                        Entry("", "", "FAILED", t?.javaClass?.name, t?.message, "ENGINE"),
                    )
                }
                return
            }
            val t = result.throwable.orElse(null)
            val (cls, method) = when (val source = identifier.source.orElse(null)) {
                is MethodSource -> source.className to source.methodName
                else -> "" to identifier.displayName
            }
            val status = when (result.status) {
                TestExecutionResult.Status.SUCCESSFUL -> "PASSED"
                TestExecutionResult.Status.ABORTED -> "SKIPPED"
                TestExecutionResult.Status.FAILED -> "FAILED"
            }
            val stage = if (result.status == TestExecutionResult.Status.FAILED && t != null && isBeforeEachFailure(cls, t)) {
                "BEFORE_EACH"
            } else {
                "TEST"
            }
            out.add(Entry(cls, method, status, t?.javaClass?.name, t?.message, stage))
        }

        override fun executionSkipped(identifier: TestIdentifier, reason: String) {
            if (!identifier.parentId.isPresent || identifier.isContainer) return
            val (cls, method) = when (val source = identifier.source.orElse(null)) {
                is MethodSource -> source.className to source.methodName
                else -> "" to identifier.displayName
            }
            out.add(Entry(cls, method, "SKIPPED", null, null, "TEST"))
        }

        /**
         * BeforeEach не даёт отдельного события в Platform API: если исключение
         * прошло через @BeforeEach-метод класса (или суперкласса) — это стадия
         * подготовки, не тело теста. Аннотация резолвится по имени: replay-jar
         * не тянет junit-jupiter-api (Launcher и API берутся из classpath проекта).
         */
        private fun isBeforeEachFailure(testClass: String, t: Throwable): Boolean {
            val annotation = runCatching { Class.forName("org.junit.jupiter.api.BeforeEach") }.getOrNull()
                ?: return false
            @Suppress("UNCHECKED_CAST")
            val annotationClass = annotation as Class<out Annotation>
            var cls: Class<*>? = runCatching { Class.forName(testClass) }.getOrNull() ?: return false
            while (cls != null) {
                for (m in cls.declaredMethods) {
                    if (m.isAnnotationPresent(annotationClass)) {
                        if (t.stackTrace.any { it.className == cls!!.name && it.methodName == m.name }) {
                            return true
                        }
                    }
                }
                cls = cls.superclass
            }
            return false
        }
    }

    private fun buildJson(entries: List<Entry>): String = buildString {
        append("{\"results\":[")
        entries.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append("{\"class\":").append(quote(e.cls))
            append(",\"method\":").append(quote(e.method))
            append(",\"status\":").append(quote(e.status))
            append(",\"failureType\":").append(e.failureType?.let { quote(it) } ?: "null")
            append(",\"failureMessage\":").append(e.failureMessage?.let { quote(it) } ?: "null")
            append(",\"stage\":").append(quote(e.stage))
            append('}')
        }
        append("],\"jvmStarted\":true}")
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\u000c")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
