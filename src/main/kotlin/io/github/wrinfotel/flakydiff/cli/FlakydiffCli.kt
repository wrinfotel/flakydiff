package io.github.wrinfotel.flakydiff.cli

import io.github.wrinfotel.flakydiff.ddmin.diagnose
import io.github.wrinfotel.flakydiff.reader.OrderSource
import io.github.wrinfotel.flakydiff.reader.ReadOptions
import io.github.wrinfotel.flakydiff.reader.ReadResult
import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.reader.readReports
import io.github.wrinfotel.flakydiff.replay.JvmReplayHarness
import io.github.wrinfotel.flakydiff.replay.ReplayHarness
import io.github.wrinfotel.flakydiff.replay.ReplayResult
import io.github.wrinfotel.flakydiff.replay.prepareProject
import io.github.wrinfotel.flakydiff.verdict.VerdictContext
import io.github.wrinfotel.flakydiff.verdict.buildVerdict
import io.github.wrinfotel.flakydiff.verdict.parseVerdictJson
import io.github.wrinfotel.flakydiff.verdict.renderJson
import io.github.wrinfotel.flakydiff.verdict.renderText
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/**
 * CLI flakydiff (план Task 5.3): diagnose / replay / report. `--project` обязателен
 * у diagnose и replay: из него prepareProject строит classpath зондов; проект
 * готовится ОДИН раз на запуск. Каждый зонд = свежая JVM; стоимость митигируется
 * флагами diagnose — текст митигации продублирован в описании команды (--help).
 */
@Command(
    name = "flakydiff",
    mixinStandardHelpOptions = true,
    version = ["flakydiff v0.1.0"],
    description = [
        "Поиск минимального набора polluter-тестов для flaky-жертвы.",
        "Каждый зонд — свежая JVM; вердикты по §4.4 спеки, честные флаги недостоверности.",
    ],
    subcommands = [DiagnoseCommand::class, ReplayCommand::class, ReportCommand::class],
)
class FlakydiffCli : Callable<Int> {
    @Spec
    lateinit var spec: CommandSpec

    override fun call(): Int {
        spec.commandLine().usage(spec.commandLine().out)
        return 2
    }
}

/** Точка входа дистрибуции: Main-Class = FlakydiffCliKt (top-level main). */
fun main(args: Array<String>) {
    exitProcess(CommandLine(FlakydiffCli()).execute(*args))
}

private const val DEFAULT_REPEAT = 3

/** Жертва/метод: FQCN#method. Ссылка без '#' допустима только для prefix (весь класс). */
private fun parseRef(s: String): TestRef? {
    val idx = s.indexOf('#')
    if (idx <= 0 || idx == s.length - 1) return null
    return TestRef(s.substring(0, idx), s.substring(idx + 1))
}

/**
 * probeClasspath: flakydiff-*-replay.jar рядом с текущим jar (дистрибуция, Task 5.4).
 * В dev-запусках (классы из target/) его нет — берём classpath текущего процесса:
 * ReplayMain доступен, порядок «classpath проекта первым» соблюдён харнессом.
 */
private fun probeClasspath(): List<Path> {
    val here = Path.of(FlakydiffCli::class.java.protectionDomain.codeSource.location.toURI())
    val dir = if (here.fileName?.toString()?.endsWith(".jar") == true) here.parent else here
    Files.list(dir).use { stream ->
        val replay = stream.filter { it.fileName.toString().endsWith("-replay.jar") }.findFirst().orElse(null)
        if (replay != null) return listOf(replay)
    }
    return System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { Path.of(it) }
}

@Command(
    name = "diagnose",
    mixinStandardHelpOptions = true,
    description = [
        "Диагностика order-dependency жертвы по XML-отчётам surefire.",
        "Каждый зонд — свежая JVM: стоимость растёт с длиной prefix.",
        "Митигации стоимости: --prefix-file (ручное сужение prefix),",
        "--max-classes (кап размера prefix), --min-fail-ratio (строже критерий",
        "воспроизведения — меньше сужений), --victim-timeout/--probe-timeout (рамки зонда).",
    ],
)
open class DiagnoseCommand : Callable<Int> {
    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["--project"], required = true,
        description = ["Корень Maven-проекта жертвы (обязателен: из него готовится classpath зондов)."],
    )
    lateinit var project: Path

    @Option(names = ["--reports"], required = true, description = ["Директория XML-отчётов surefire."])
    lateinit var reports: Path

    @Option(names = ["--victim"], required = true, description = ["Жертва: FQCN#method."])
    lateinit var victim: String

    @Option(
        names = ["--sequential"],
        description = ["Порядок классов из XML достоверен (снимает order_unreliable только при точных timestamp без срабатывания эвристики)."],
    )
    var sequential: Boolean = false

    @Option(names = ["--no-forks"], description = ["Форки surefire исключены (forks_possible=no в вердикте)."])
    var noForks: Boolean = false

    @Option(
        names = ["--prefix-file"],
        description = ["Файл с prefix построчно (FQCN#method или FQCN — весь класс): ручное сужение вместо записанного порядка."],
    )
    var prefixFile: Path? = null

    @Option(
        names = ["--max-classes"],
        description = ["Кап размера prefix: при превышении берётся первая половина записанного prefix + предупреждение."],
    )
    var maxClasses: Int? = null

    @Option(
        names = ["--min-fail-ratio"], defaultValue = "2",
        description = ["Сколько повторов жертвы из 3 обязаны упасть, чтобы считать «воспроизведено» (дефолт 2)."],
    )
    var minFailRatio: Int = 2

    @Option(
        names = ["--victim-timeout"],
        description = ["Таймаут повтора жертвы, сек (перекрывает clamp из длительностей XML)."],
    )
    var victimTimeoutSec: Long? = null

    @Option(names = ["--probe-timeout"], description = ["Рамка на весь процесс зонда, сек (kill по истечении)."])
    var probeTimeoutSec: Long? = null

    @Option(names = ["--out"], description = ["Директория для JSON вердикта (файл verdict.json)."])
    var outDir: Path? = null

    override fun call(): Int {
        val victimRef = parseRef(victim) ?: run {
            spec.commandLine().err.println("invalid --victim '$victim': expected FQCN#method")
            return 2
        }

        val read = readReports(reports, ReadOptions(sequential = if (sequential) true else null, noForks = noForks))
        read.errors.forEach {
            spec.commandLine().err.println("reader: ${it.message}${it.cause?.let { c -> ": $c" } ?: ""}")
        }
        if (read.run.entries.none { it.ref == victimRef }) {
            spec.commandLine().err.println("victim $victim not found in reports")
            return 1
        }

        val prefix: List<TestRef> = if (prefixFile != null) {
            val fromFile = readPrefixFile(prefixFile!!)
            when {
                fromFile == null -> return 2
                fromFile.isEmpty() -> {
                    spec.commandLine().err.println("prefix file is empty: $prefixFile")
                    return 2
                }
                else -> fromFile
            }
        } else {
            val recorded = read.run.entries.takeWhile { it.ref != victimRef }.map { it.ref }
            val cap = maxClasses
            if (cap != null && recorded.size > cap) {
                val half = recorded.take(recorded.size / 2)
                spec.commandLine().out.println(
                    "warning: recorded prefix (${recorded.size} entries) exceeds --max-classes $cap; " +
                        "narrowed to first half (${half.size} entries)",
                )
                half
            } else {
                recorded
            }
        }

        val durations = read.run.entries.associate { it.ref to it.durationMs }
        val verdict = try {
            val harness = buildHarness(durations)
            val d = diagnose(harness, victimRef, prefix, repeat = DEFAULT_REPEAT, threshold = minFailRatio)
            buildVerdict(d, verdictContext(read, victimRef))
        } catch (t: Throwable) {
            spec.commandLine().err.println("diagnose failed: ${t.javaClass.simpleName}: ${t.message}")
            return 1
        }

        spec.commandLine().out.println(renderText(verdict))
        outDir?.let { dir ->
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("verdict.json"), renderJson(verdict))
        }
        return 0
    }

    /**
     * Точка подмены в тестах (unit-скорость, без mvn/JVM — план Task 5.3). В проде:
     * prepareProject (classpath-кэш по SHA-256(pom) в target/flakydiff-cache) +
     * JvmReplayHarness с таймаутами из длительностей XML.
     */
    protected open fun buildHarness(durationsMs: Map<TestRef, Long>): ReplayHarness {
        val info = prepareProject(project, project.resolve("target/flakydiff-cache"))
        info.warnings.forEach { spec.commandLine().err.println("warning: $it") }
        return JvmReplayHarness(
            project = info,
            probeClasspath = probeClasspath(),
            durationsMs = durationsMs,
            victimTimeoutMs = victimTimeoutSec?.let { it * 1000 },
            probeTimeoutMs = probeTimeoutSec?.let { it * 1000 },
        )
    }

    private fun verdictContext(read: ReadResult, victimRef: TestRef): VerdictContext = VerdictContext(
        projectDir = project.toAbsolutePath().normalize().toString(),
        repeat = DEFAULT_REPEAT,
        orderUnreliable = read.orderUnreliable,
        orderUnreliableReason = when {
            !read.orderUnreliable -> null
            read.orderSource == OrderSource.MTIME_HEURISTIC ->
                "порядок восстановлен по mtime файлов (точных timestamp нет)"
            else -> "порядок из XML не подтверждён (--sequential не дал достоверности)"
        },
        forksPossible = read.forksPossible,
        markedFlakyInRun = victimRef in read.run.markedFlaky,
    )

    private fun readPrefixFile(f: Path): List<TestRef>? {
        if (!Files.exists(f)) {
            spec.commandLine().err.println("prefix file not found: $f")
            return null
        }
        return Files.readAllLines(f).asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val idx = line.indexOf('#')
                if (idx <= 0) TestRef(line, "") else TestRef(line.substring(0, idx), line.substring(idx + 1))
            }
            .toList()
    }
}

@Command(
    name = "replay",
    mixinStandardHelpOptions = true,
    description = [
        "Один зонд в свежей JVM: prefix → victim×repeat — воспроизведение вручную,",
        "без диагностики. Exit-коды: 0 = reproduced, 1 = not reproduced, 2 = инфра/ошибка.",
    ],
)
open class ReplayCommand : Callable<Int> {
    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--project"], required = true, description = ["Корень Maven-проекта жертвы."])
    lateinit var project: Path

    @Option(
        names = ["--prefix"], defaultValue = "",
        description = ["Предшественники через запятую: FQCN[#method]; без #method — весь класс. Пусто — только жертва."],
    )
    var prefix: String = ""

    @Option(names = ["--victim"], required = true, description = ["Жертва: FQCN#method."])
    lateinit var victim: String

    @Option(names = ["--repeat"], defaultValue = "3", description = ["Повторов жертвы (дефолт 3)."])
    var repeat: Int = 3

    protected open fun buildHarness(): ReplayHarness {
        val info = prepareProject(project, project.resolve("target/flakydiff-cache"))
        info.warnings.forEach { spec.commandLine().err.println("warning: $it") }
        return JvmReplayHarness(info, probeClasspath())
    }

    override fun call(): Int {
        val victimRef = parseRef(victim) ?: run {
            spec.commandLine().err.println("invalid --victim '$victim': expected FQCN#method")
            return 2
        }
        val prefixRefs = prefix.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val idx = line.indexOf('#')
            if (idx <= 0) TestRef(line, "") else TestRef(line.substring(0, idx), line.substring(idx + 1))
        }
        return when (val r = buildHarness().replay(prefixRefs, victimRef, repeat)) {
            is ReplayResult.Reproduced -> {
                spec.commandLine().out.println("REPRODUCED ${r.failures}/${r.attempts}")
                r.failure.type?.let { spec.commandLine().out.println("failure: $it") }
                r.failure.message?.let { spec.commandLine().out.println("message: $it") }
                0
            }
            is ReplayResult.NotReproduced -> {
                spec.commandLine().out.println("NOT-REPRODUCED ${r.passes}/${r.attempts} pass")
                1
            }
            is ReplayResult.InfraError -> {
                spec.commandLine().err.println("INFRA-ERROR: ${r.cause}")
                2
            }
        }
    }
}

@Command(
    name = "report",
    mixinStandardHelpOptions = true,
    description = ["Рендер вердикта из JSON-файла: текст §4.4 (дефолт) или JSON."],
)
class ReportCommand : Callable<Int> {
    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--verdict"], required = true, description = ["Файл JSON вердикта."])
    lateinit var verdict: Path

    @Option(names = ["--format"], defaultValue = "text", description = ["text | json (дефолт text)."])
    var format: String = "text"

    override fun call(): Int {
        if (!Files.exists(verdict)) {
            spec.commandLine().err.println("verdict file not found: $verdict")
            return 2
        }
        val v = try {
            parseVerdictJson(Files.readString(verdict))
        } catch (t: Exception) {
            spec.commandLine().err.println("invalid verdict JSON: ${t.message}")
            return 2
        }
        when (format) {
            "text" -> spec.commandLine().out.println(renderText(v))
            "json" -> spec.commandLine().out.println(renderJson(v))
            else -> {
                spec.commandLine().err.println("invalid --format '$format': expected text|json")
                return 2
            }
        }
        return 0
    }
}
