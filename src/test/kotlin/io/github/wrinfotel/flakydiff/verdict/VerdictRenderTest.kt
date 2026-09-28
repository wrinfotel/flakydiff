package io.github.wrinfotel.flakydiff.verdict

import io.github.wrinfotel.flakydiff.ddmin.Diagnosis
import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.reader.TestRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 5.2 (план): текстовый рендер — в точности формат §4.4 спеки (VERDICT-заголовок,
 * строки victim/polluter/evidence/flags/repro); эталоны — КОНСТАНТЫ здесь, с тестовыми
 * именами. Примеры §4.4 — иллюстрация формата, а не контракт. Один polluter — одна
 * строка polluter, два — две. JSON — те же поля camelCase (kotlinx-serialization).
 */
class VerdictRenderTest {

    private val victim = TestRef("com.example.VictimTest", "flaky")
    private val polluter = TestRef("com.example.PolluterTest", "pollutes")
    private val failure = TestFailure("org.opentest4j.AssertionFailedError", "field not clean", null)

    private fun orderDependency(
        polluters: List<TestRef> = listOf(polluter),
        rf: Int = 3,
        ra: Int = 3,
        cf: Int = 5,
        ca: Int = 5,
    ) = Diagnosis.OrderDependency(victim, polluters, failure, rf, ra, cf, ca)

    private fun ctx(
        orderUnreliable: Boolean = false,
        orderUnreliableReason: String? = null,
        forksPossible: Boolean = true,
        markedFlakyInRun: Boolean = false,
    ) = VerdictContext(
        projectDir = "path/to/project",
        repeat = 3,
        orderUnreliable = orderUnreliable,
        orderUnreliableReason = orderUnreliableReason,
        forksPossible = forksPossible,
        markedFlakyInRun = markedFlakyInRun,
    )

    @Test
    fun `text render - order dependency matches golden format`() {
        val text = renderText(buildVerdict(orderDependency(), ctx()))

        assertEquals(
            """
            VERDICT: ORDER-DEPENDENCY (reproduced 3/3, confirmed fresh-process 5/5)
            victim:   com.example.VictimTest#flaky
            polluter: com.example.PolluterTest#pollutes
            evidence: изолированно 3/3 pass; после polluter 3/3 fail
            flags:    order_unreliable=no  forks_possible=yes
            repro:    flakydiff replay --project path/to/project --prefix com.example.PolluterTest --victim com.example.VictimTest#flaky
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `text render - two polluters render two lines in recorded order`() {
        val p1 = TestRef("com.example.PolluterOneTest", "poisonOne")
        val p2 = TestRef("com.example.PolluterTwoTest", "poisonTwo")
        val text = renderText(buildVerdict(orderDependency(listOf(p1, p2), rf = 2, ra = 3, cf = 4, ca = 5), ctx()))

        assertEquals(
            """
            VERDICT: ORDER-DEPENDENCY (reproduced 2/3, confirmed fresh-process 4/5)
            victim:   com.example.VictimTest#flaky
            polluter: com.example.PolluterOneTest#poisonOne
            polluter: com.example.PolluterTwoTest#poisonTwo
            evidence: изолированно 3/3 pass; после polluter 2/3 fail
            flags:    order_unreliable=no  forks_possible=yes
            repro:    flakydiff replay --project path/to/project --prefix com.example.PolluterOneTest,com.example.PolluterTwoTest --victim com.example.VictimTest#flaky
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `text render - unconfirmed shows honest confirmed rate`() {
        val d = Diagnosis.Unconfirmed(
            victim, listOf(polluter),
            reproducedFailures = 2, reproducedAttempts = 3,
            confirmationFailures = 3, confirmationAttempts = 5,
        )
        val text = renderText(buildVerdict(d, ctx()))

        assertTrue(text.startsWith("VERDICT: UNCONFIRMED (reproduced 2/3, confirmed fresh-process 3/5)"), text)
    }

    @Test
    fun `text render - not isolated shows victim and unstable flag, no repro line`() {
        val text = renderText(buildVerdict(Diagnosis.NotIsolated(victim, victimUnstableInIsolation = true), ctx()))

        assertEquals(
            """
            VERDICT: NOT-ISOLATED
            victim:   com.example.VictimTest#flaky
            evidence: жертва флейкует изолированно (без prefix)
            flags:    order_unreliable=no  forks_possible=yes  victim_unstable_in_isolation=yes
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `text render - order unreliable with reason and no forks`() {
        val text = renderText(
            buildVerdict(orderDependency(), ctx(orderUnreliable = true, orderUnreliableReason = "mtime соседних классов совпадают", forksPossible = false)),
        )

        assertTrue(text.contains("flags:    order_unreliable=yes (mtime соседних классов совпадают)  forks_possible=no"), text)
    }

    @Test
    fun `text render - marked flaky in run surfaces as flag`() {
        val text = renderText(buildVerdict(orderDependency(), ctx(markedFlakyInRun = true)))

        assertTrue(text.contains("marked_flaky_in_run=yes"), text)
    }

    @Test
    fun `text render - infra broken shows diagnostics, no victim and no repro`() {
        val text = renderText(buildVerdict(Diagnosis.InfraBroken("classpath broken: no com.acme.Foo"), ctx()))

        assertEquals(
            """
            VERDICT: INFRA-BROKEN
            evidence: зонд прерван инфра-ошибкой (чинить окружение, не тест)
            diagnostics: classpath broken: no com.acme.Foo
            flags:    order_unreliable=no  forks_possible=yes
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `text render - unsupported carries reason in evidence, no duplicate diagnostics line`() {
        val text = renderText(
            buildVerdict(
                Diagnosis.Unsupported(TestRef("com.example.VictimTest", "check(int)[1]"), "check(int)[1] не адресуется напрямую, fallback не сработал"),
                ctx(),
            ),
        )

        assertEquals(
            """
            VERDICT: UNSUPPORTED
            victim:   com.example.VictimTest#check(int)[1]
            evidence: check(int)[1] не адресуется напрямую, fallback не сработал
            flags:    order_unreliable=no  forks_possible=yes
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `text render - not reproduced has honest rate in header, no repro line`() {
        val text = renderText(buildVerdict(Diagnosis.NotReproduced(victim, passes = 3, attempts = 3), ctx()))

        assertEquals(
            """
            VERDICT: NOT-REPRODUCED (reproduced 0/3)
            victim:   com.example.VictimTest#flaky
            evidence: полный prefix не воспроизводит падение: 3/3 pass
            flags:    order_unreliable=no  forks_possible=yes
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `json roundtrip preserves verdict`() {
        val v = buildVerdict(orderDependency(), ctx(orderUnreliable = true, orderUnreliableReason = "r", markedFlakyInRun = true))

        val parsed = parseVerdictJson(renderJson(v))

        assertEquals(v, parsed)
    }

    @Test
    fun `json uses camelCase keys and underscored type name`() {
        // Рукаописный JSON фиксирует имена ключей (план Task 5.2: те же поля camelCase,
        // тип — подчёркивания) — парсинг обязан принимать ровно такой документ.
        val raw = """
            {
              "type": "ORDER_DEPENDENCY",
              "victim": {"testClass": "com.example.VictimTest", "method": "flaky"},
              "polluters": [{"testClass": "com.example.PolluterTest", "method": "pollutes"}],
              "evidence": "изолированно 3/3 pass; после polluter 3/3 fail",
              "reproducedRate": "3/3",
              "confirmedRate": "5/5",
              "orderUnreliable": false,
              "orderUnreliableReason": null,
              "forksPossible": true,
              "markedFlakyInRun": false,
              "victimUnstableInIsolation": false,
              "reproCommand": "flakydiff replay --project path/to/project --prefix com.example.PolluterTest --victim com.example.VictimTest#flaky",
              "diagnostics": null
            }
        """.trimIndent()

        val parsed = parseVerdictJson(raw)

        assertEquals(VerdictType.ORDER_DEPENDENCY, parsed.type)
        assertEquals(victim, parsed.victim)
        assertEquals("3/3", parsed.reproducedRate)
        assertEquals("5/5", parsed.confirmedRate)
        assertFalse(parsed.orderUnreliable)
    }

    @Test
    fun `json rendering contains type name with underscores`() {
        val json = renderJson(buildVerdict(orderDependency(), ctx()))

        assertTrue(json.contains("ORDER_DEPENDENCY"), json)
        assertTrue(json.contains("\"reproducedRate\""), json)
        assertTrue(json.contains("\"confirmedRate\""), json)
    }
}
