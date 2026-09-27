package io.github.wrinfotel.flakydiff.reader

import kotlin.math.roundToLong
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private fun newDocument(xml: String) =
    DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(xml.byteInputStream())

private fun durationMs(tc: Element): Long {
    val time = tc.getAttribute("time")
    if (time.isEmpty()) return 0L
    return (time.toDouble() * 1000).roundToLong()
}

fun parse(xml: String): List<TestExecution> {
    val doc = newDocument(xml)
    val testcases = doc.getElementsByTagName("testcase")
    val result = mutableListOf<TestExecution>()
    for (i in 0 until testcases.length) {
        val tc = testcases.item(i) as Element
        val ref = TestRef(tc.getAttribute("classname"), tc.getAttribute("name"))
        val failureElement = tc.getElementsByTagName("failure")
        if (failureElement.length > 0) {
            val f = failureElement.item(0) as Element
            result += TestExecution(
                ref = ref,
                status = Status.FAILED,
                durationMs = durationMs(tc),
                failure = TestFailure(
                    type = f.getAttribute("type").ifEmpty { null },
                    message = f.getAttribute("message").ifEmpty { null },
                    content = f.textContent.ifEmpty { null },
                ),
            )
        } else {
            result += TestExecution(ref, Status.PASSED, durationMs(tc), failure = null)
        }
    }
    return result
}
