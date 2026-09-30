package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.replay.ReplayHarness
import io.github.wrinfotel.flakydiff.replay.ReplayResult

/** The result of ddmin (plan Task 3.4, spec §4.3). */
sealed interface DdminResult {
    /**
     * A 1-minimal (or plateau) set of predecessors in the recorded order.
     * [lastFailure] — the failure of the last FAIL probe (for verdict diagnostics).
     */
    data class Minimized(val polluters: List<TestRef>, val lastFailure: TestFailure?) : DdminResult

    /**
     * Two InfraErrors in a row on the same probe. The ddmin result is DISCARDED:
     * the verdict contains neither polluters nor a repro command — only the diagnostics
     * ("fix the environment, not the test"); no narrowing is performed on an infra failure.
     */
    data class InfraBroken(val cause: String) : DdminResult
}

private sealed interface ProbeAttempt {
    data class Completed(val outcome: ProbeOutcome, val failure: TestFailure?) : ProbeAttempt
    data class Broken(val cause: String) : ProbeAttempt
}

/**
 * ddmin over chunks: classic delta debugging over the set of predecessors.
 * Adaptation: the elements cannot be reordered — chunks are taken contiguously in the
 * recorded order, complements preserve the order (verified by a test).
 *
 * 1. n = 2; split the prefix into n contiguous chunks.
 * 2. Probe "prefix minus chunk": FAILS → prefix := prefix minus chunk, n = max(2, n-1).
 * 3. A full pass without narrowing → n is doubled; when the chunks are already single elements
 *    (n >= the size) and there is no narrowing — stop: the current set is the result (plateau).
 * 4. A single element: probe FAILS → single polluter. PASSES/WEAK — plateau
 *    (the set is minimal by construction; confirmation provides the honesty).
 * 5. Policy §4.3: InfraError on a probe → one retry of the same probe; a second
 *    InfraError → INFRA_BROKEN. INFRA never narrows the set.
 */
fun ddmin(
    harness: ReplayHarness,
    prefix: List<TestRef>,
    victim: TestRef,
    repeat: Int = 3,
    threshold: Int = 2,
): DdminResult {
    // Step 1 already established reproduction on the full prefix — it cannot arrive empty
    require(prefix.isNotEmpty()) { "ddmin needs a non-empty prefix (step 1 must reproduce first)" }

    var current = prefix
    var n = 2
    var lastFailure: TestFailure? = null

    while (true) {
        if (current.size == 1) {
            return when (val attempt = probeWithInfraRetry(harness, current, victim, repeat, threshold)) {
                is ProbeAttempt.Broken -> DdminResult.InfraBroken(attempt.cause)
                is ProbeAttempt.Completed -> when (attempt.outcome) {
                    ProbeOutcome.FAILS -> DdminResult.Minimized(current, attempt.failure ?: lastFailure)
                    ProbeOutcome.PASSES, ProbeOutcome.WEAK -> DdminResult.Minimized(current, lastFailure)
                    ProbeOutcome.INFRA -> error("unreachable: retry wrapper resolves INFRA")
                }
            }
        }

        var removed = false
        for (range in splitChunks(current.size, n)) {
            val complement = current.filterIndexed { idx, _ -> idx !in range }
            when (val attempt = probeWithInfraRetry(harness, complement, victim, repeat, threshold)) {
                is ProbeAttempt.Broken -> return DdminResult.InfraBroken(attempt.cause)
                is ProbeAttempt.Completed -> when (attempt.outcome) {
                    ProbeOutcome.FAILS -> {
                        current = complement
                        lastFailure = attempt.failure
                        n = maxOf(2, n - 1)
                        removed = true
                        break
                    }
                    // PASSES: the chunk is the culprit — keep it. WEAK: a weak signal conservatively
                    // does NOT narrow (spec §4.3) — an explicit branch, equated to neither PASSES nor FAILS.
                    ProbeOutcome.PASSES, ProbeOutcome.WEAK -> {}
                    ProbeOutcome.INFRA -> error("unreachable: retry wrapper resolves INFRA")
                }
            }
        }
        if (removed) continue

        // A full pass without narrowing: plateau at single-element chunks, otherwise double n
        if (n >= current.size) return DdminResult.Minimized(current, lastFailure)
        n = minOf(2 * n, current.size)
    }
}

/**
 * A probe with the §4.3 policy: the first InfraError — one retry of the SAME probe;
 * a second InfraError → Broken (INFRA_BROKEN at the caller). A successful retry
 * (FAILS/PASSES/WEAK) continues ddmin as if nothing had happened.
 */
private fun probeWithInfraRetry(
    harness: ReplayHarness,
    prefix: List<TestRef>,
    victim: TestRef,
    repeat: Int,
    threshold: Int,
): ProbeAttempt {
    val first = harness.replay(prefix, victim, repeat)
    val firstOutcome = classify(first, threshold)
    if (firstOutcome != ProbeOutcome.INFRA) {
        return ProbeAttempt.Completed(firstOutcome, (first as? ReplayResult.Reproduced)?.failure)
    }
    val retry = harness.replay(prefix, victim, repeat)
    val retryOutcome = classify(retry, threshold)
    if (retryOutcome != ProbeOutcome.INFRA) {
        return ProbeAttempt.Completed(retryOutcome, (retry as? ReplayResult.Reproduced)?.failure)
    }
    return ProbeAttempt.Broken((retry as ReplayResult.InfraError).cause)
}

/**
 * Contiguous chunks in the recorded order: count = min(n, size) segments,
 * the remainder is distributed over the first chunks (base+1). The chunks cover the
 * whole list and each is non-empty — narrowing strictly shrinks the set.
 */
internal fun splitChunks(size: Int, n: Int): List<IntRange> {
    val count = n.coerceAtMost(size)
    val base = size / count
    val remainder = size % count
    val ranges = mutableListOf<IntRange>()
    var start = 0
    for (i in 0 until count) {
        val len = base + if (i < remainder) 1 else 0
        ranges += start until (start + len)
        start += len
    }
    return ranges
}
