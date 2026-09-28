package io.github.wrinfotel.flakydiff.reader

import kotlinx.serialization.Serializable

/** Ссылка на тест: FQCN класса + имя метода. @Serializable — вердикт уходит в JSON (Task 5.2). */
@Serializable
data class TestRef(val testClass: String, val method: String)

enum class Status { PASSED, FAILED, SKIPPED }

data class TestFailure(val type: String?, val message: String?, val content: String?)

data class TestExecution(
    val ref: TestRef,
    val status: Status,
    val durationMs: Long,
    val failure: TestFailure?,
)

data class TestRun(val entries: List<TestExecution>, val markedFlaky: Set<TestRef> = emptySet())

data class ReaderError(val message: String, val cause: String? = null)

/** Результат разбора одного XML-файла: по записи на testcase, rerun-элементы отдельно.
 *  timestamps — точные старты testcase (surefire ≥3.5.5 reportTestTimestamp);
 *  suiteTimestampMs — атрибут timestamp на уровне testsuite (фолбэк для класса). */
data class ParsedFile(
    val entries: List<TestExecution>,
    val markedFlaky: Set<TestRef>,
    val timestamps: Map<TestRef, Long> = emptyMap(),
    val suiteTimestampMs: Long? = null,
)

/**
 * Склеивает разобранные файлы в прогон: (class, method) встречается один раз —
 * берём первый прогон (без dedup жертва станет предшественником самой себя).
 */
fun buildTestRun(files: List<ParsedFile>): TestRun {
    val seen = mutableSetOf<TestRef>()
    val entries = mutableListOf<TestExecution>()
    val marked = mutableSetOf<TestRef>()
    for (file in files) {
        marked += file.markedFlaky
        for (entry in file.entries) {
            if (seen.add(entry.ref)) entries += entry
        }
    }
    return TestRun(entries, marked)
}


