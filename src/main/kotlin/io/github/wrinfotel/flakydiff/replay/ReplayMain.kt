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
 * A probe (spec §4.2): runs prefix → victim×repeat in a SINGLE JVM. A fresh JVM
 * for the whole probe is guaranteed by the harness (Task 2.5); state between
 * victim repeats is not reset. The result is JSON in --report, not stdout.
 *
 * JSON serialization is manual: neither kotlinx-serialization nor picocli is
 * packed into the replay-jar (Task 2.3), so both argument parsing and JSON
 * here are dependency-free.
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
                // Prefix order is the check semantics (spec §4.2): each class of the
                // recorded order is executed with its OWN discovery request, and
                // consecutive references to one class are collapsed into a single
                // request. A single shared request would leave the order to the
                // engine's discretion: the project's ClassOrderer (Random/ClassName)
                // would rearrange classes relative to the recorded run.
                //
                // Selection by class + postDiscoveryFilter on the name prefix (plan
                // Task 4.3): a display-name reference of a parameterized test
                // (check(int)[2]) is addressed by class + a leaf filter on the name
                // prefix — we don't parse parameter types out of the display name, the
                // engine runs all invocations. A bare selectMethod by name doesn't fit:
                // it looks for a no-arg method and misses a parameterized one.
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

                // Honesty (plan Task 4.3): every prefix reference must be addressed —
                // by a report row (a method) or an executed container (a whole class).
                // An unaddressed reference makes the probe invalid: an honest
                // flakydiff.prefix-unresolved row (stage=ENGINE → InfraError on the
                // harness side), not a silent skip.
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
            // Victim repeats are separate discovery requests in the SAME JVM: the
            // platform deduplicates identical selectors within one request, and state
            // is not reset between execute() calls — pollution semantics are preserved.
            // Each repeat gets its own kill watchdog (--victim-timeout).
            for (attempt in 1..opts.repeat) {
                val completed = executeWithTimeout(launcher, victimRequest, listener, opts.victimTimeoutSec)
                if (!completed) {
                    // A hung repeat aborts the remaining ones: timeout -> InfraError,
                    // repeating the hang is pointless.
                    entries.add(
                        Entry(
                            // victim.method non-null is guaranteed by parseArgs (--victim must be FQCN#method)
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
            // An engine/Launcher-level error lands honestly in the report (stage=ENGINE),
            // not silently in the exit code.
            entries.add(Entry("", "", "FAILED", t.javaClass.name, t.message, "ENGINE"))
        }

        Files.writeString(Path.of(opts.report), buildJson(entries))
        return 0
    }

    private fun request(selectors: List<org.junit.platform.engine.DiscoverySelector>): LauncherDiscoveryRequest =
        LauncherDiscoveryRequestBuilder.request().selectors(selectors).build()

    /**
     * Consecutive references to one class form one group (one discovery request);
     * the group order = the recorded class order. Method order within a class is
     * the engine's order, as in the recorded Jupiter run.
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
     * A display-name invocation (contains '(' or '[') is addressed by the base
     * method name; a plain method name contains none of these characters — Java/Kotlin
     * identifiers (except backtick names) do not allow them.
     */
    private fun isDisplayName(method: String): Boolean = method.contains('(') || method.contains('[')

    private fun effectiveMethodName(method: String): String =
        if (isDisplayName(method)) method.substringBefore('(').substringBefore('[').trim() else method

    /**
     * Leaf filter for "selection by class + name prefix": a whole-class reference
     * admits any of its methods; a plain name requires an exact match; a display-name
     * matches the method name or a leaf display-name with the "base(" prefix
     * (invocations of a parameterized test).
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
     * A victim repeat runs on a worker thread; when the budget expires the thread
     * is interrupted. The listener is sealed BEFORE the interrupt so that the
     * interrupted Jupiter phase does not append an extra entry to the report.
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
            // N=1 — the WEAK/FAILS buckets are undefined; N=2 — "≥2 of 2" means
            // determinism, not flaky mode. Spec: N>=3 is required.
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

        /** Class containers that started execution — for the honesty of whole-class prefix references. */
        val executedClasses = mutableSetOf<String>()

        /** No longer accepts events — called on victim-timeout before the interrupt. */
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
                // Successful containers (classes, engine) do not reach the report; a
                // container failure = a class/engine-level error: @BeforeAll → BEFORE_ALL,
                // engine/Launcher → ENGINE.
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
         * BeforeEach gets no dedicated event in the Platform API: if an exception
         * came through a class's (or superclass's) @BeforeEach method — that is a
         * preparation stage, not the test body. The annotation is resolved by name:
         * the replay-jar does not pull in junit-jupiter-api (the Launcher and the API
         * come from the project's classpath).
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
