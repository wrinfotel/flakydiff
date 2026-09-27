package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.replay.ReplayResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Бакеты предиката ddmin (план Task 3.1, спека §4.3): считаем УПАВШИЕ повторы жертвы.
 * «2/3 прошло» = WEAK, «1/3 прошло» = FAILS — бакеты однозначны, пересечений нет.
 */
class ProbeTest {

    private val failure = TestFailure("java.lang.AssertionError", "boom", null)

    @Test
    fun `2 of 3 failed is FAILS`() {
        val r = ReplayResult.Reproduced(2, 3, failure)
        assertEquals(ProbeOutcome.FAILS, classify(r))
    }

    @Test
    fun `3 of 3 failed is FAILS`() {
        val r = ReplayResult.Reproduced(3, 3, failure)
        assertEquals(ProbeOutcome.FAILS, classify(r))
    }

    @Test
    fun `0 of 3 failed is PASSES`() {
        val r = ReplayResult.NotReproduced(3, 3)
        assertEquals(ProbeOutcome.PASSES, classify(r))
    }

    @Test
    fun `1 of 3 failed is WEAK`() {
        // «2/3 прошло» = слабый сигнал, консервативно не сужает ddmin
        val r = ReplayResult.NotReproduced(2, 3)
        assertEquals(ProbeOutcome.WEAK, classify(r))
    }

    @Test
    fun `infra error is INFRA regardless of stage`() {
        assertEquals(ProbeOutcome.INFRA, classify(ReplayResult.InfraError("classpath broken")))
    }

    @Test
    fun `threshold is configurable`() {
        // --min-fail-ratio: threshold=1 → 1/3 упало уже FAILS
        assertEquals(ProbeOutcome.FAILS, classify(ReplayResult.NotReproduced(2, 3), threshold = 1))
        // threshold=3 → 2/3 упало ещё WEAK (строгий детерминизм, а не флейк-режим)
        assertEquals(ProbeOutcome.WEAK, classify(ReplayResult.Reproduced(2, 3, failure), threshold = 3))
        assertEquals(ProbeOutcome.FAILS, classify(ReplayResult.Reproduced(3, 3, failure), threshold = 3))
    }

    @Test
    fun `threshold below 1 is rejected`() {
        val r = ReplayResult.Reproduced(2, 3, failure)
        assertThrows(IllegalArgumentException::class.java) { classify(r, threshold = 0) }
    }
}
