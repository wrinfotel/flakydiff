package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.replay.ReplayResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ddmin по чанкам (план Task 3.4, спека §4.3): классический delta debugging
 * по множеству предшественников, элементы НЕ переупорядочиваются — чанки
 * берутся подряд в записанном порядке.
 */
class DdminTest {

    private val victim = TestRef("com.example.VictimTest", "flaky")
    private val failure = TestFailure("java.lang.AssertionError", "boom", null)

    private fun ballast(n: Int): List<TestRef> = (0 until n).map { TestRef("com.example.BallastTest", "m$it") }

    private fun polluterAt(index: Int): TestRef = TestRef("com.example.PolluterTest", "p$index")

    /** Каждый прозваниваемый prefix — подпоследовательность исходного в записанном порядке. */
    private fun isSubsequence(sub: List<TestRef>, of: List<TestRef>): Boolean {
        var i = 0
        for (x in of) {
            if (i < sub.size && x == sub[i]) i++
        }
        return i == sub.size
    }

    @Test
    fun `a single polluter at position 100 of 200 is found`() {
        val prefix = ballast(200).toMutableList()
        val polluter = polluterAt(100)
        prefix[100] = polluter
        val h = FakeHarness { p, v, rep ->
            check(v == victim)
            if (polluter in p) ReplayResult.Reproduced(rep, rep, failure) else ReplayResult.NotReproduced(rep, rep)
        }

        val result = ddmin(h, prefix, victim)

        assertEquals(DdminResult.Minimized(listOf(polluter), failure), result)
        // порядок элементов не менялся: каждый зонд — подпоследовательность записанного prefix
        assertTrue(h.calls.all { isSubsequence(it.prefix, prefix) }, "order was reordered")
    }

    @Test
    fun `b two polluters - exactly one found (fixed behavior)`() {
        val prefix = ballast(200).toMutableList()
        val p1 = polluterAt(50)
        val p2 = polluterAt(150)
        prefix[50] = p1
        prefix[150] = p2
        val h = FakeHarness { p, v, rep ->
            check(v == victim)
            if (p1 in p || p2 in p) ReplayResult.Reproduced(rep, rep, failure) else ReplayResult.NotReproduced(rep, rep)
        }

        val result = ddmin(h, prefix, victim)

        val found = (result as DdminResult.Minimized).polluters
        assertEquals(1, found.size, "1-minimal содержит одного из двух виновников (зафиксировано спекой)")
        assertTrue(found.single() == p1 || found.single() == p2)
    }

    @Test
    fun `c infra on probe and on retry - INFRA_BROKEN without polluters`() {
        val h = FakeHarness { _, _, _ -> ReplayResult.InfraError("env dead") }

        val result = ddmin(h, ballast(8), victim)

        assertEquals(DdminResult.InfraBroken("env dead"), result)
        // политика §4.3: сужения НЕТ — первый зонд + один повтор, стоп
        assertEquals(2, h.calls.size)
        assertEquals(h.calls[0].prefix, h.calls[1].prefix)
    }

    @Test
    fun `c2 infra retried once then probe succeeds - ddmin continues and narrows`() {
        val prefix = ballast(8).toMutableList()
        val polluter = polluterAt(3)
        prefix[3] = polluter
        var firstCall = true
        val h = FakeHarness { p, v, rep ->
            check(v == victim)
            if (firstCall) {
                firstCall = false
                ReplayResult.InfraError("transient")
            } else if (polluter in p) {
                ReplayResult.Reproduced(rep, rep, failure)
            } else {
                ReplayResult.NotReproduced(rep, rep)
            }
        }

        val result = ddmin(h, prefix, victim)

        assertEquals(DdminResult.Minimized(listOf(polluter), failure), result)
        // второй вызов — повтор ТОГО ЖЕ зонда (тот же prefix)
        assertEquals(h.calls[0].prefix, h.calls[1].prefix)
    }

    @Test
    fun `d weak on every probe never narrows - prefix stays intact`() {
        val prefix = ballast(6)
        // 1/3 упало = WEAK: отдельный исход предиката, не приравнивается ни к PASSES, ни к FAILS
        val h = FakeHarness { _, _, _ -> ReplayResult.NotReproduced(2, 3) }

        val result = ddmin(h, prefix, victim)

        assertEquals(DdminResult.Minimized(prefix, null), result)
    }

    @Test
    fun `empty prefix is rejected - step 1 guarantees reproduction first`() {
        val h = FakeHarness { _, _, _ -> ReplayResult.NotReproduced(3, 3) }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ddmin(h, emptyList(), victim)
        }
    }
}
