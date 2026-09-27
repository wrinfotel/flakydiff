package io.github.wrinfotel.flakydiff.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SurefireXmlParserTest {

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/xml/$name")!!.readBytes().decodeToString()

    @Test
    fun `parses surefire xml with one passing and one failing testcase`() {
        val xml = resource("one-pass-one-fail.xml")

        val executions = parse(xml)

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
}
