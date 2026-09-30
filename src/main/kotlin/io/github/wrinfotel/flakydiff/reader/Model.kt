package io.github.wrinfotel.flakydiff.reader

import kotlinx.serialization.Serializable

/** A reference to a test: the class FQCN + the method name. @Serializable — the verdict goes into JSON (Task 5.2). */
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

/** The result of parsing one XML file: an entry per testcase, rerun elements tracked separately.
 *  timestamps — the exact testcase start times (surefire ≥3.5.5 reportTestTimestamp);
 *  suiteTimestampMs — the timestamp attribute at the testsuite level (a fallback for the class). */
data class ParsedFile(
    val entries: List<TestExecution>,
    val markedFlaky: Set<TestRef>,
    val timestamps: Map<TestRef, Long> = emptyMap(),
    val suiteTimestampMs: Long? = null,
)

/**
 * Merges the parsed files into a run: a (class, method) pair must occur only once —
 * the first run is kept (without dedup the victim would become its own predecessor).
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


