package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.replay.ReplayResult

/**
 * Buckets of the ddmin predicate (spec §4.3). We count the FAILED victim repeats:
 * - FAILS — at least threshold of the attempts failed (default ≥2/3);
 * - PASSES — none failed (the full rate);
 * - WEAK — some failed but fewer than threshold (by default exactly 1 of 3) — a weak signal,
 *   conservatively does not narrow ddmin; a separate outcome, NOT equated to PASSES/FAILS;
 * - INFRA — InfraError (classpath/setup/timeout): not a test failure, policy §4.3.
 */
enum class ProbeOutcome { FAILS, PASSES, WEAK, INFRA }

/**
 * Folds ReplayResult into a bucket. The threshold is configured by the --min-fail-ratio
 * flag (default 2 of 3): a "≥1 of 3" criterion together with a noisy predicate breaks
 * the monotonicity of ddmin — spontaneous triggerings produce false narrowing.
 */
fun classify(r: ReplayResult, threshold: Int = 2): ProbeOutcome {
    require(threshold >= 1) { "threshold must be >= 1, got $threshold" }
    return when (r) {
        is ReplayResult.InfraError -> ProbeOutcome.INFRA
        is ReplayResult.Reproduced -> bucket(r.failures, threshold)
        is ReplayResult.NotReproduced -> bucket(r.attempts - r.passes, threshold)
    }
}

private fun bucket(failures: Int, threshold: Int): ProbeOutcome = when {
    failures >= threshold -> ProbeOutcome.FAILS
    failures == 0 -> ProbeOutcome.PASSES
    // a repeat that did not pass (failed or was skipped) — conservatively not "passed"
    else -> ProbeOutcome.WEAK
}
