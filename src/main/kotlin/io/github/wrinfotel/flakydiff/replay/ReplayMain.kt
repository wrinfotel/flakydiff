package io.github.wrinfotel.flakydiff.replay

import org.junit.platform.engine.FilterResult
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.PostDiscoveryFilter
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
                // Порядок prefix — семантика проверки (спека §4.2): каждый класс
                // записанного порядка исполняется СВОИМ discovery-запросом, подряд
                // идущие ссылки одного класса сворачиваются в один запрос. Один
                // общий запрос отдавал бы порядок на откуп движку: ClassOrderer
                // проекта (Random/ClassName) переставил бы классы относительно
                // записанного прогона.
                //
                // Отбор по классу + postDiscoveryFilter префиксом имени (план
                // Task 4.3): display-name ссылка параметризованного теста
                // (check(int)[2]) адресуется классом + фильтром листьев по префиксу
                // имени — типы параметров из display-name не парсим, движок прогоняет
                // все invocations. Голый selectMethod по имени не подходит: он ищет
                // no-arg метод и не находит параметризованный.
                for ((cls, refs) in groupConsecutiveByClass(opts.prefix)) {
                    val groupRequest = LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectClass(cls))
                        .filters(
                            PostDiscoveryFilter { descriptor ->
                                if (refs.any { leafMatches(it, descriptor) }) {
                                    FilterResult.included(null)
                                } else {
                                    FilterResult.excluded(null)
                                }
                            },
                        )
                        .build()
                    launcher.execute(groupRequest, listener)
                }

                // Честность (план Task 4.3): каждая prefix-ссылка обязана быть
                // адресованной — строкой отчёта (метод) или исполненным контейнером
                // (целый класс). Неадресованная ссылка делает зонд невалидным:
                // честная строка flakydiff.prefix-unresolved (stage=ENGINE →
                // InfraError у харнесса), не молчаливый пропуск.
                val prefixRows = entries.toList()
                for (ref in opts.prefix) {
                    val addressed = if (ref.method == null) {
                        listener.executedClasses.contains(ref.cls)
                    } else {
                        prefixRows.any { it.cls == ref.cls && it.method == effectiveMethodName(ref.method) }
                    }
                    if (!addressed) {
                        entries.add(
                            Entry(
                                ref.cls, ref.method ?: "", "FAILED",
                                "flakydiff.prefix-unresolved",
                                "prefix entry not addressable: ${ref.cls}#${ref.method ?: "<class>"}, " +
                                    "fallback did not resolve it",
                                "ENGINE",
                            ),
                        )
                    }
                }
            }
            val victimSelector = DiscoverySelectors.selectMethod(opts.victim.cls, opts.victim.method)
            val victimRequest = request(listOf(victimSelector))
            // Повторы жертвы — отдельные discovery-запросы в ТОЙ ЖЕ JVM: платформа
            // дедуплицирует одинаковые селекторы внутри одного запроса, а состояние
            // между execute() не сбрасывается — семантика загрязнения сохраняется.
            // На каждый повтор — свой kill-watchdog (--victim-timeout).
            for (attempt in 1..opts.repeat) {
                val completed = executeWithTimeout(launcher, victimRequest, listener, opts.victimTimeoutSec)
                if (!completed) {
                    // Зависший повтор обрывает остальные: timeout -> InfraError,
                    // повторять зависание бессмысленно.
                    entries.add(
                        Entry(
                            // victim.method не-null гарантирован parseArgs (--victim обязан быть FQCN#method)
                            opts.victim.cls, opts.victim.method!!, "FAILED",
                            "flakydiff.victim-timeout",
                            "victim repeat $attempt exceeded ${opts.victimTimeoutSec}s",
                            "TEST",
                        ),
                    )
                    break
                }
            }
        } catch (t: Throwable) {
            // Ошибка на уровне движка/Launcher — честно попадает в отчёт (stage=ENGINE),
            // а не молча в exit-код.
            entries.add(Entry("", "", "FAILED", t.javaClass.name, t.message, "ENGINE"))
        }

        Files.writeString(Path.of(opts.report), buildJson(entries))
        return 0
    }

    private fun request(selectors: List<org.junit.platform.engine.DiscoverySelector>): LauncherDiscoveryRequest =
        LauncherDiscoveryRequestBuilder.request().selectors(selectors).build()

    /**
     * Подряд идущие ссылки одного класса — одна группа (один discovery-запрос);
     * порядок групп = записанный порядок классов. Порядок методов внутри класса —
     * порядок движка, как и в записанном Jupiter-прогоне.
     */
    private fun groupConsecutiveByClass(prefix: List<PrefixRef>): List<Pair<String, List<PrefixRef>>> {
        val groups = mutableListOf<Pair<String, MutableList<PrefixRef>>>()
        for (ref in prefix) {
            val last = groups.lastOrNull()
            if (last != null && last.first == ref.cls) {
                last.second.add(ref)
            } else {
                groups.add(ref.cls to mutableListOf(ref))
            }
        }
        return groups
    }

    /**
     * display-name invocation (содержит '(' или '[') адресуется по базовому
     * имени метода; обычное имя метода не содержит этих символов — идентификаторы
     * Java/Kotlin (кроме backtick-имен) их не допускают.
     */
    private fun isDisplayName(method: String): Boolean = method.contains('(') || method.contains('[')

    private fun effectiveMethodName(method: String): String =
        if (isDisplayName(method)) method.substringBefore('(').substringBefore('[').trim() else method

    /**
     * Фильтр листьев для «отбора по классу + префикс имени»: ссылка на весь
     * класс пропускает любой его метод; обычное имя — точное совпадение;
     * display-name — имя метода или display-name листа с префиксом «base(»
     * (invocations параметризованного теста).
     */
    private fun leafMatches(ref: PrefixRef, descriptor: TestDescriptor): Boolean {
        val source = descriptor.source.orElse(null)
        if (source !is MethodSource || source.className != ref.cls) return false
        val method = ref.method ?: return true
        if (!isDisplayName(method)) return source.methodName == method
        val base = effectiveMethodName(method)
        return source.methodName == base || descriptor.displayName.startsWith("$base(")
    }

    /**
     * Повтор жертвы выполняется в рабочем потоке; по истечении рамки поток
     * прерывается. Листенер герметизируется ДО interrupt, чтобы прерванная
     * Jupiter-фаза не дописала в отчёт лишнюю запись.
     */
    private fun executeWithTimeout(
        launcher: Launcher,
        request: LauncherDiscoveryRequest,
        listener: CollectingListener,
        timeoutSec: Long?,
    ): Boolean {
        if (timeoutSec == null) {
            launcher.execute(request, listener)
            return true
        }
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "flakydiff-victim-repeat").apply { isDaemon = true }
        }
        try {
            val future = executor.submit { launcher.execute(request, listener) }
            return try {
                future.get(timeoutSec, TimeUnit.SECONDS)
                true
            } catch (e: TimeoutException) {
                listener.seal()
                future.cancel(true)
                false
            }
        } finally {
            executor.shutdownNow()
        }
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
        @Volatile
        private var sealed = false

        /** Контейнеры-классы, начавшие исполнение, — для честности prefix-ссылок на весь класс. */
        val executedClasses = mutableSetOf<String>()

        /** Больше не принимает события — вызывается при victim-timeout до interrupt. */
        fun seal() {
            sealed = true
        }

        override fun executionStarted(identifier: TestIdentifier) {
            if (sealed) return
            if (!identifier.parentId.isPresent || !identifier.isContainer) return
            (identifier.source.orElse(null) as? ClassSource)?.let { executedClasses.add(it.className) }
        }

        override fun executionFinished(identifier: TestIdentifier, result: TestExecutionResult) {
            if (sealed) return
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
            if (sealed) return
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
