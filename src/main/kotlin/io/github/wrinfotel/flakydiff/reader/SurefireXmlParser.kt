package io.github.wrinfotel.flakydiff.reader

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException

sealed interface ParseOutcome {
    data class Ok(val file: ParsedFile) : ParseOutcome
    data class Failed(val error: ReaderError) : ParseOutcome
}

private fun newDocumentBuilder() =
    DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder()

private fun durationMs(tc: Element): Long {
    val time = tc.getAttribute("time")
    if (time.isEmpty()) return 0L
    return (time.toDouble() * 1000).roundToLong()
}

private fun failurePayload(el: Element): TestFailure = TestFailure(
    type = el.getAttribute("type").ifEmpty { null },
    message = el.getAttribute("message").ifEmpty { null },
    content = el.textContent.ifEmpty { null },
)

fun parse(xml: String): ParseOutcome {
    val doc = try {
        newDocumentBuilder().parse(xml.byteInputStream())
    } catch (e: SAXException) {
        return ParseOutcome.Failed(ReaderError("Broken XML report", cause = e.message))
    } catch (e: java.io.IOException) {
        return ParseOutcome.Failed(ReaderError("Failed to read XML report", cause = e.message))
    }

    val testcases = doc.getElementsByTagName("testcase")
    val result = mutableListOf<TestExecution>()
    val markedFlaky = mutableSetOf<TestRef>()
    for (i in 0 until testcases.length) {
        val tc = testcases.item(i) as Element
        val ref = TestRef(tc.getAttribute("classname"), tc.getAttribute("name"))
        var status = Status.PASSED
        var failure: TestFailure? = null
        var child: Node? = tc.firstChild
        while (child != null) {
            if (child is Element) {
                when (child.tagName) {
                    "failure", "error" -> {
                        status = Status.FAILED
                        failure = failurePayload(child)
                    }
                    // rerun-элементы (rerunFailingTestsCount): flaky* — упал, перезапуск прошёл,
                    // rerun* — упал и перезапуск тоже. Первый прогон — сам testcase: прямой
                    // <failure>/<error>, а для flaky* — сам rerun-элемент описывает первую попытку.
                    "flakyFailure", "flakyError", "rerunFailure", "rerunError" -> {
                        markedFlaky += ref
                        if (failure == null) {
                            status = Status.FAILED
                            failure = failurePayload(child)
                        }
                    }
                    "skipped" -> status = Status.SKIPPED
                }
            }
            child = child.nextSibling
        }
        result += TestExecution(ref, status, durationMs(tc), failure)
    }
    return ParseOutcome.Ok(ParsedFile(result, markedFlaky))
}
