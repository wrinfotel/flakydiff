package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.replay.ReplayHarness
import io.github.wrinfotel.flakydiff.replay.ReplayResult

/**
 * Result of step 0 (spec §4.3): the victim in isolation, WITHOUT the prefix.
 * Any NOT_ISOLATED — not order-dependency: move on to another hypothesis.
 */
sealed interface Step0Result {
    /** All victim repeats passed — order-dependency is possible, proceed to step 1. */
    data object Isolated : Step0Result

    /**
     * The test fails/flakes on its own. [victimUnstableInIsolation] — the
     * victim_unstable_in_isolation flag: true ⟺ the WEAK bucket ("2/3 passed" — a suspicion
     * that the test itself is flaky); false — the FAILS bucket (the test simply fails on its own).
     */
    data class NotIsolated(val victimUnstableInIsolation: Boolean) : Step0Result

    /** Infra is broken — fix the environment, not the test (diagnostics included). */
    data class InfraBroken(val cause: String) : Step0Result
}

/**
 * Step 0: the victim in isolation (without the prefix) passes repeat/repeat — otherwise we don't go into ddmin.
 */
fun step0(harness: ReplayHarness, victim: TestRef, repeat: Int = 3): Step0Result {
    val result = harness.replay(emptyList(), victim, repeat)
    return when (val outcome = classify(result)) {
        ProbeOutcome.PASSES -> Step0Result.Isolated
        // the flag ⟺ WEAK by construction (plan Task 3.2): a separate WEAK_FLAKY is not needed
        ProbeOutcome.WEAK -> Step0Result.NotIsolated(victimUnstableInIsolation = true)
        ProbeOutcome.FAILS -> Step0Result.NotIsolated(victimUnstableInIsolation = false)
        ProbeOutcome.INFRA -> Step0Result.InfraBroken((result as ReplayResult.InfraError).cause)
    }
}

/** Result of step 1 (spec §4.3): the full prefix + the victim must fail. */
sealed interface Step1Result {
    /**
     * The full prefix reproduces the failure — go into ddmin. [failures]/[attempts] —
     * the honest probe rate; goes into the verdict (reproducedRate, the "after polluter" evidence).
     */
    data class ReproducedWithFullPrefix(val failures: Int, val attempts: Int) : Step1Result

    /** The full prefix does NOT reproduce it (weak signal included) — likely races/timing, out of scope for v1. */
    data class NotReproduced(val passes: Int, val attempts: Int) : Step1Result

    /** Infra is broken — fix the environment, not the test. */
    data class InfraBroken(val cause: String) : Step1Result
}

/**
 * Step 1: the full prefix + the victim must fail (per the bucket criteria). PASSES and WEAK
 * conservatively lead to NOT_REPRODUCED — the honest rate is preserved for the verdict.
 */
fun step1(harness: ReplayHarness, prefix: List<TestRef>, victim: TestRef, repeat: Int = 3): Step1Result {
    val result = harness.replay(prefix, victim, repeat)
    return when (val outcome = classify(result)) {
        ProbeOutcome.FAILS -> {
            val (failures, attempts) = when (result) {
                is ReplayResult.Reproduced -> result.failures to result.attempts
                is ReplayResult.NotReproduced -> (result.attempts - result.passes) to result.attempts
                is ReplayResult.InfraError -> error("unreachable: INFRA handled below")
            }
            Step1Result.ReproducedWithFullPrefix(failures, attempts)
        }
        ProbeOutcome.PASSES, ProbeOutcome.WEAK -> {
            val (passes, attempts) = when (result) {
                is ReplayResult.NotReproduced -> result.passes to result.attempts
                is ReplayResult.Reproduced -> (result.attempts - result.failures) to result.attempts
                is ReplayResult.InfraError -> error("unreachable: INFRA handled below")
            }
            Step1Result.NotReproduced(passes, attempts)
        }
        ProbeOutcome.INFRA -> Step1Result.InfraBroken((result as ReplayResult.InfraError).cause)
    }
}

/**
 * The diagnosis outcome (spec §4.3–4.4): names in code/JSON use underscores,
 * in the §5 checklist the same names with hyphens (NOT_ISOLATED = NOT-ISOLATED).
 */
sealed interface Diagnosis {
    /** Order-dependency found and confirmed by a fresh JVM: ≥4 of 5 victim repeats failed. */
    data class OrderDependency(
        val victim: TestRef,
        val polluters: List<TestRef>,
        val lastFailure: TestFailure?,
        /** Step 1 rate (full prefix) — for the verdict (reproducedRate, evidence). */
        val reproducedFailures: Int,
        val reproducedAttempts: Int,
        val confirmationFailures: Int,
        val confirmationAttempts: Int,
    ) : Diagnosis

    /** ddmin found a set, but confirmation fell short of ≥4/5 — the honest rate is recorded. */
    data class Unconfirmed(
        val victim: TestRef,
        val polluters: List<TestRef>,
        /** Step 1 rate (full prefix) — for the verdict (reproducedRate, evidence). */
        val reproducedFailures: Int,
        val reproducedAttempts: Int,
        val confirmationFailures: Int,
        val confirmationAttempts: Int,
    ) : Diagnosis

    /** Step 0: the victim fails/flakes on its own — not order-dependency. */
    data class NotIsolated(val victim: TestRef, val victimUnstableInIsolation: Boolean) : Diagnosis

    /** Step 1: the full prefix does not reproduce the failure — likely races/timing (out of scope for v1). */
    data class NotReproduced(val victim: TestRef, val passes: Int, val attempts: Int) : Diagnosis

    /** Infra broken (the unified §4.3 policy): fix the environment, not the test; no narrowing results are shown. */
    data class InfraBroken(val cause: String) : Diagnosis

    /**
     * A victim shape v1 does not diagnose (plan Task 4.3): display-name
     * invocation of a parameterized test (check(int)[1]). The "class +
     * name-prefix" fallback runs all invocations at once and does not isolate
     * a single repeat — an honest refusal with the test name, WITHOUT probes and without narrowing.
     */
    data class Unsupported(val victim: TestRef, val reason: String) : Diagnosis
}

/** Confirmation — a separate probe in a fresh JVM; its criterion is deliberately stricter than ddmin's: ≥4 of 5 (80% vs 67%). */
private const val CONFIRM_REPEAT = 5
private const val CONFIRM_MIN_FAILURES = 4

/**
 * The full pipeline: step 0 (isolation) → step 1 (full prefix) → ddmin →
 * fresh-process confirmation. Each stage can honestly stop the diagnosis.
 */
fun diagnose(
    harness: ReplayHarness,
    victim: TestRef,
    prefix: List<TestRef>,
    repeat: Int = 3,
    threshold: Int = 2,
): Diagnosis {
    // victim display-name (parameterized test) — before any probes: repeating
    // a single invocation in a fresh JVM is impossible, probing it would only produce garbage.
    if (victim.method.contains('(') || victim.method.contains('[')) {
        return Diagnosis.Unsupported(
            victim,
            "${victim.method} не адресуется напрямую, fallback не сработал: " +
                "параметризованный тест нельзя повторить поштучно в свежей JVM",
        )
    }

    when (val s0 = step0(harness, victim, repeat)) {
        is Step0Result.NotIsolated -> return Diagnosis.NotIsolated(victim, s0.victimUnstableInIsolation)
        is Step0Result.InfraBroken -> return Diagnosis.InfraBroken(s0.cause)
        Step0Result.Isolated -> {}
    }

    val s1 = when (val r = step1(harness, prefix, victim, repeat)) {
        is Step1Result.NotReproduced -> return Diagnosis.NotReproduced(victim, r.passes, r.attempts)
        is Step1Result.InfraBroken -> return Diagnosis.InfraBroken(r.cause)
        is Step1Result.ReproducedWithFullPrefix -> r
    }

    val minimal = when (val d = ddmin(harness, prefix, victim, repeat, threshold)) {
        is DdminResult.InfraBroken -> return Diagnosis.InfraBroken(d.cause)
        is DdminResult.Minimized -> d
    }

    val confirmation = harness.replay(minimal.polluters, victim, CONFIRM_REPEAT)
    val failures = when (confirmation) {
        is ReplayResult.Reproduced -> confirmation.failures
        is ReplayResult.NotReproduced -> confirmation.attempts - confirmation.passes
        // Unified policy §4.3: InfraError ≠ a test failure — INFRA_BROKEN, not UNCONFIRMED
        is ReplayResult.InfraError -> return Diagnosis.InfraBroken(confirmation.cause)
    }
    return if (failures >= CONFIRM_MIN_FAILURES) {
        Diagnosis.OrderDependency(
            victim, minimal.polluters, minimal.lastFailure,
            s1.failures, s1.attempts, failures, CONFIRM_REPEAT,
        )
    } else {
        Diagnosis.Unconfirmed(victim, minimal.polluters, s1.failures, s1.attempts, failures, CONFIRM_REPEAT)
    }
}
