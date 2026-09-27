package io.github.wrinfotel.flakydiff.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SurefireXmlParserTest {

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/xml/$name")!!.readBytes().decodeToString()

    private fun parseOk(xml: String): List<TestExecution> =
        (parse(xml) as ParseOutcome.Ok).file.entries

    @Test
    fun `parses surefire xml with one passing and one failing testcase`() {
        val xml = resource("one-pass-one-fail.xml")

        val executions = parseOk(xml)

        assertEquals(2, executions.size)

        assertEquals(TestRef("com.acme.OrderServiceTest", "shouldCharge"), executions[0].ref)
        assertEquals(Status.PASSED, executions[0].status)
        assertEquals(200L, executions[0].durationMs)
        assertNull(executions[0].failure)

        assertEquals(TestRef("com.acme.OrderServiceTest", "shouldRender"), executions[1].ref)
        assertEquals(Status.FAILED, executions[1].status)
        assertEquals(150L, executions[1].durationMs)
        assertEquals(
            TestFailure(type = "java.lang.AssertionError", message = "x", content = "stack trace body"),
            executions[1].failure,
        )
    }

    @Test
    fun `error element maps to FAILED and skipped to SKIPPED`() {
        val executions = parseOk(resource("one-error-one-skipped.xml"))

        assertEquals(2, executions.size)

        val errored = executions[0]
        assertEquals(TestRef("com.acme.OrderServiceTest", "shouldCharge"), errored.ref)
        assertEquals(Status.FAILED, errored.status)
        assertEquals(
            TestFailure(type = "java.lang.IllegalStateException", message = "boom", content = "stack of error"),
            errored.failure,
        )

        val skipped = executions[1]
        assertEquals(TestRef("com.acme.OrderServiceTest", "shouldChargeRefund"), skipped.ref)
        assertEquals(Status.SKIPPED, skipped.status)
    }

    @Test
    fun `parses gradle legacy junit xml the same way`() {
        val executions = parseOk(resource("gradle-format.xml"))

        assertEquals(2, executions.size)
        assertEquals(TestRef("com.acme.OrderServiceTest", "shouldCharge"), executions[0].ref)
        assertEquals(Status.PASSED, executions[0].status)
        assertEquals(TestRef("com.acme.OrderServiceTest", "shouldRender"), executions[1].ref)
        assertEquals(Status.FAILED, executions[1].status)
        assertEquals(
            TestFailure(
                type = "junit.framework.ComparisonFailure",
                message = "expected: 1 but was: 2",
                content = "org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n\tat com.acme.OrderServiceTest.shouldRender(OrderServiceTest.java:42)\n",
            ),
            executions[1].failure,
        )
    }

    @Test
    fun `unknown elements and attributes are ignored silently`() {
        val xml = """
            <testsuite name="com.acme.WeirdTest" time="0.1" unknownSuiteAttr="42" tests="1">
              <unknownChild anything="yes"/>
              <testcase name="plain" classname="com.acme.WeirdTest" time="0.1" unknownCaseAttr="7">
                <system-out><![CDATA[out]]></system-out>
              </testcase>
            </testsuite>
        """.trimIndent()

        val executions = parseOk(xml)

        assertEquals(1, executions.size)
        assertEquals(Status.PASSED, executions[0].status)
        assertNull(executions[0].failure)
    }

    @Test
    fun `broken xml yields domain ReaderError, not an exception`() {
        val outcome = parse(resource("broken.xml"))

        assertTrue(outcome is ParseOutcome.Failed)
        val error = (outcome as ParseOutcome.Failed).error
        assertTrue(error.message.isNotBlank())
        assertTrue(error.cause != null)
    }

    @Test
    fun `rerun elements dedup to one entry, first run taken, victim marked flaky`() {
        val outcome = parse(resource("rerun-flaky.xml"))
        assertTrue(outcome is ParseOutcome.Ok)
        val file = (outcome as ParseOutcome.Ok).file

        val run = buildTestRun(listOf(file))

        assertEquals(3, run.entries.size)

        val flaky = run.entries.first { it.ref.method == "flakyPass" }
        assertEquals(Status.FAILED, flaky.status)
        assertEquals(
            TestFailure("java.lang.AssertionError", "first attempt failed", "first attempt stack"),
            flaky.failure,
        )

        val rerun = run.entries.first { it.ref.method == "rerunFail" }
        assertEquals(Status.FAILED, rerun.status)
        assertEquals(
            TestFailure("java.lang.AssertionError", "original fail", "original stack"),
            rerun.failure,
        )

        val plain = run.entries.first { it.ref.method == "plainPass" }
        assertEquals(Status.PASSED, plain.status)

        assertEquals(2, run.markedFlaky.size)
        assertTrue(run.markedFlaky.contains(TestRef("com.acme.FlakeTest", "flakyPass")))
        assertTrue(run.markedFlaky.contains(TestRef("com.acme.FlakeTest", "rerunFail")))
    }

    @Test
    fun `duplicate testcase entries across files keep first run`() {
        val first = ParsedFile(
            entries = listOf(TestExecution(TestRef("com.acme.FlakeTest", "flakyPass"), Status.FAILED, 100L, null)),
            markedFlaky = emptySet(),
        )
        val second = ParsedFile(
            entries = listOf(TestExecution(TestRef("com.acme.FlakeTest", "flakyPass"), Status.PASSED, 200L, null)),
            markedFlaky = emptySet(),
        )

        val run = buildTestRun(listOf(first, second))

        assertEquals(1, run.entries.size)
        assertEquals(Status.FAILED, run.entries[0].status)
        assertEquals(100L, run.entries[0].durationMs)
    }
}
