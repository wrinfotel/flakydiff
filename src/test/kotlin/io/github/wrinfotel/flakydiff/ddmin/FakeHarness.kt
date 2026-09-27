package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.replay.ReplayHarness
import io.github.wrinfotel.flakydiff.replay.ReplayResult

/**
 * Детерминированный fake-harness для unit-тестов ddmin (план Task 3.2):
 * каждый вызов фиксируется, отклик задаётся лямбдой — никаких JVM.
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

        /** true = повтор жертвы упал. ≥2 упавших → Reproduced, иначе NotReproduced (как JvmReplayHarness). */
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
