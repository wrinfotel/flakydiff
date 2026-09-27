package io.github.wrinfotel.flakydiff.reader

import java.nio.file.Files
import java.nio.file.Path

enum class OrderSource { TIMESTAMP, MTIME_HEURISTIC }

data class ReadResult(
    val run: TestRun,
    val orderSource: OrderSource,
    val orderUnreliable: Boolean,
    val errors: List<ReaderError> = emptyList(),
)

private const val GRANULARITY_MS = 1000L

/** Читает директорию XML-отчётов и восстанавливает порядок классов.
 *  Семантика `sequential` (спека §4.1, блокирующее правило):
 *  null/false → order_unreliable=true всегда; true снимает флаг только при
 *  источнике TIMESTAMP без срабатывания эвристики недостоверности. */
fun readDirectory(dir: Path, sequential: Boolean?): ReadResult {
    val xmlFiles = Files.list(dir).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }
            .sorted()
            .toList()
    }

    val parsed = mutableListOf<Pair<Path, ParsedFile>>()
    val errors = mutableListOf<ReaderError>()
    for (file in xmlFiles) {
        when (val outcome = parse(Files.readString(file))) {
            is ParseOutcome.Ok -> parsed += file to outcome.file
            is ParseOutcome.Failed -> errors += outcome.error
        }
    }

    data class ClassTiming(val className: String, val startMs: Long, val exact: Boolean)

    val timings = mutableListOf<ClassTiming>()
    for ((path, file) in parsed) {
        val mtimeMs = Files.getLastModifiedTime(path).toMillis()
        val byClass = file.entries.groupBy { it.ref.testClass }
        for ((className, classEntries) in byClass) {
            val exactStarts = classEntries.mapNotNull { file.timestamps[it.ref] }
            val exact = exactStarts.isNotEmpty() || file.suiteTimestampMs != null
            val start = when {
                exactStarts.isNotEmpty() -> exactStarts.min()
                file.suiteTimestampMs != null -> file.suiteTimestampMs
                else -> mtimeMs - classEntries.sumOf { it.durationMs }
            }
            timings += ClassTiming(className, start, exact)
        }
    }

    // один класс может быть в нескольких файлах — берём самый ранний старт
    val classStarts = LinkedHashMap<String, Long>()
    val classExact = HashMap<String, Boolean>()
    for (t in timings) {
        val existing = classStarts[t.className]
        if (existing == null || t.startMs < existing) classStarts[t.className] = t.startMs
        classExact[t.className] = (classExact[t.className] ?: false) || t.exact
    }

    val allExact = classExact.values.isNotEmpty() && classExact.values.all { it }
    val source = if (allExact) OrderSource.TIMESTAMP else OrderSource.MTIME_HEURISTIC

    val sorted = classStarts.entries.sortedWith(compareBy({ it.value }, { it.key })).toList()
    val heuristicTriggered = !allExact ||
        sorted.zipWithNext().any { (a, b) -> b.value - a.value < GRANULARITY_MS }

    val orderUnreliable = when (sequential) {
        true -> source != OrderSource.TIMESTAMP || heuristicTriggered
        null, false -> true
    }

    val byClass = parsed.flatMap { it.second.entries }.groupBy { it.ref.testClass }
    val seen = mutableSetOf<TestRef>()
    val orderedEntries = mutableListOf<TestExecution>()
    for ((className, _) in sorted) {
        for (entry in byClass[className].orEmpty()) {
            if (seen.add(entry.ref)) orderedEntries += entry
        }
    }
    val run = TestRun(orderedEntries, parsed.flatMapTo(mutableSetOf()) { it.second.markedFlaky })

    return ReadResult(run, source, orderUnreliable, errors)
}
