package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.replay.ReplayResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Buckets of the ddmin predicate (plan Task 3.1, spec §4.3): we count FAILED victim replays.
 * "2/3 passed" = WEAK, "1/3 passed" = FAILS — the buckets are unambiguous, no overlap.
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
        // "2/3 passed" = a weak signal, conservatively does not narrow ddmin
        val r = ReplayResult.NotReproduced(2, 3)
        assertEquals(ProbeOutcome.WEAK, classify(r))
    }

    @Test
    fun `infra error is INFRA regardless of stage`() {
        assertEquals(ProbeOutcome.INFRA, classify(ReplayResult.InfraError("classpath broken")))
    }

    @Test
    fun `threshold is configurable`() {
        // --min-fail-ratio: threshold=1 → 1/3 failed is already FAILS
        assertEquals(ProbeOutcome.FAILS, classify(ReplayResult.NotReproduced(2, 3), threshold = 1))
        // threshold=3 → 2/3 failed is still WEAK (strict determinism, not flake mode)
        assertEquals(ProbeOutcome.WEAK, classify(ReplayResult.Reproduced(2, 3, failure), threshold = 3))
        assertEquals(ProbeOutcome.FAILS, classify(ReplayResult.Reproduced(3, 3, failure), threshold = 3))
    }

    @Test
    fun `threshold below 1 is rejected`() {
        val r = ReplayResult.Reproduced(2, 3, failure)
        assertThrows(IllegalArgumentException::class.java) { classify(r, threshold = 0) }
    }
}
