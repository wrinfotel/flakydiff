package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.replay.ReplayHarness
import io.github.wrinfotel.flakydiff.replay.ReplayResult

/**
 * Deterministic fake harness for the ddmin unit tests (plan Task 3.2):
 * every call is recorded, the response is given by a lambda — no JVMs.
 */
internal class FakeHarness(
    private val results: (prefix: List<TestRef>, victim: TestRef, repeat: Int) -> ReplayResult,
) : ReplayHarness {

    data class Call(val prefix: List<TestRef>, val victim: TestRef, val repeat: Int)

    val calls = mutableListOf<Call>()

    override fun replay(prefix: List<TestRef>, victim: TestRef, repeat: Int): ReplayResult {
        calls.add(Call(prefix.toList(), victim, repeat))
        return results(prefix, victim, repeat)
    }

    companion object {
        private val FAILURE = TestFailure("java.lang.AssertionError", "boom", null)

        /** true = a victim replay failed. ≥2 failures → Reproduced, otherwise NotReproduced (as JvmReplayHarness). */
        fun fromBooleans(outcomes: List<Boolean>, victim: TestRef): FakeHarness = FakeHarness { _, v, _ ->
            check(v == victim) { "unexpected victim probe: $v" }
            val failures = outcomes.count { it }
            if (failures >= 2) {
                ReplayResult.Reproduced(failures, outcomes.size, FAILURE)
            } else {
                ReplayResult.NotReproduced(outcomes.size - failures, outcomes.size)
            }
        }
    }
}
