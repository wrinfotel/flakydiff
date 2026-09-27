package io.github.wrinfotel.flakydiff.reader

data class TestRef(val testClass: String, val method: String)

enum class Status { PASSED, FAILED, SKIPPED }

data class TestFailure(val type: String?, val message: String?, val content: String?)

data class TestExecution(
    val ref: TestRef,
    val status: Status,
    val durationMs: Long,
    val failure: TestFailure?,
)

data class TestRun(val entries: List<TestExecution>)
