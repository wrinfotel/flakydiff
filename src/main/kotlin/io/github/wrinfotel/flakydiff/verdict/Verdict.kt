package io.github.wrinfotel.flakydiff.verdict

import io.github.wrinfotel.flakydiff.ddmin.Diagnosis
import io.github.wrinfotel.flakydiff.reader.TestRef
import kotlinx.serialization.Serializable

/**
 * Name registry (plan Task 5.1): in code/JSON — underscores, in the spec's §5 checklist —
 * the same names with hyphens (NOT_ISOLATED = NOT-ISOLATED): the same thing rendered two ways.
 */
@Serializable
enum class VerdictType {
    ORDER_DEPENDENCY,
    NOT_ISOLATED,
    NOT_REPRODUCED,
    UNCONFIRMED,
    INFRA_BROKEN,
    UNSUPPORTED,
}

@Serializable
data class DiagnoseVerdict(
    val type: VerdictType,
    val victim: TestRef?,
    val polluters: List<TestRef>,
    val evidence: String,
    val reproducedRate: String?,   // "3/3" — the step 1 rate (full prefix); null when no probe ran
    val confirmedRate: String?,    // "5/5" | null when confirmation was not reached
    val orderUnreliable: Boolean,
    val orderUnreliableReason: String?,
    val forksPossible: Boolean,
    val markedFlakyInRun: Boolean,
    val victimUnstableInIsolation: Boolean,
    // null when there are no polluters: INFRA_BROKEN, NOT_REPRODUCED, NOT_ISOLATED, UNSUPPORTED
    val reproCommand: String?,
    val diagnostics: String?,      // for UNSUPPORTED/INFRA_BROKEN
)

/**
 * Facts not present in [Diagnosis] — they live a level above (reader/CLI). [repeat] —
 * the number of step 0 repeats: Isolated ⟺ all repeat runs passed, hence the "isolated N/N pass" evidence.
 */
data class VerdictContext(
    val projectDir: String,
    val repeat: Int,
    val orderUnreliable: Boolean,
    val orderUnreliableReason: String? = null,
    val forksPossible: Boolean = true,
    val markedFlakyInRun: Boolean = false,
    /**
     * The actual timeout budget of the diagnostic victim repeat, when it exceeds the
     * default replay floor (30s): without it the repro command would kill the repeats of
     * a slow victim (replay without XML does not know the durations). null — default budget, no flag needed.
     */
    val reproVictimTimeoutSec: Long? = null,
)

/**
 * Maps the stage 3 result into the §4.4 verdict. Every Diagnosis type maps
 * unambiguously; the numbers (rates) are carried over verbatim — no silent lies.
 */
fun buildVerdict(diagnosis: Diagnosis, ctx: VerdictContext): DiagnoseVerdict = when (diagnosis) {
    is Diagnosis.OrderDependency -> DiagnoseVerdict(
        type = VerdictType.ORDER_DEPENDENCY,
        victim = diagnosis.victim,
        polluters = diagnosis.polluters,
        evidence = isolatedEvidence(ctx) +
            "; после polluter ${diagnosis.reproducedFailures}/${diagnosis.reproducedAttempts} fail",
        reproducedRate = "${diagnosis.reproducedFailures}/${diagnosis.reproducedAttempts}",
        confirmedRate = "${diagnosis.confirmationFailures}/${diagnosis.confirmationAttempts}",
        orderUnreliable = ctx.orderUnreliable,
        orderUnreliableReason = ctx.orderUnreliableReason,
        forksPossible = ctx.forksPossible,
        markedFlakyInRun = ctx.markedFlakyInRun,
        victimUnstableInIsolation = false,
        reproCommand = reproCommand(diagnosis.polluters, diagnosis.victim, ctx.projectDir, ctx.reproVictimTimeoutSec),
        diagnostics = null,
    )

    is Diagnosis.Unconfirmed -> DiagnoseVerdict(
        type = VerdictType.UNCONFIRMED,
        victim = diagnosis.victim,
        polluters = diagnosis.polluters,
        evidence = isolatedEvidence(ctx) +
            "; после polluter ${diagnosis.reproducedFailures}/${diagnosis.reproducedAttempts} fail",
        reproducedRate = "${diagnosis.reproducedFailures}/${diagnosis.reproducedAttempts}",
        confirmedRate = "${diagnosis.confirmationFailures}/${diagnosis.confirmationAttempts}",
        orderUnreliable = ctx.orderUnreliable,
        orderUnreliableReason = ctx.orderUnreliableReason,
        forksPossible = ctx.forksPossible,
        markedFlakyInRun = ctx.markedFlakyInRun,
        victimUnstableInIsolation = false,
        reproCommand = reproCommand(diagnosis.polluters, diagnosis.victim, ctx.projectDir, ctx.reproVictimTimeoutSec),
        diagnostics = null,
    )

    is Diagnosis.NotIsolated -> DiagnoseVerdict(
        type = VerdictType.NOT_ISOLATED,
        victim = diagnosis.victim,
        polluters = emptyList(),
        evidence = if (diagnosis.victimUnstableInIsolation) {
            "жертва флейкует изолированно (без prefix)"
        } else {
            "жертва падает изолированно (без prefix)"
        },
        reproducedRate = null,
        confirmedRate = null,
        orderUnreliable = ctx.orderUnreliable,
        orderUnreliableReason = ctx.orderUnreliableReason,
        forksPossible = ctx.forksPossible,
        markedFlakyInRun = ctx.markedFlakyInRun,
        victimUnstableInIsolation = diagnosis.victimUnstableInIsolation,
        reproCommand = null,
        diagnostics = null,
    )

    is Diagnosis.NotReproduced -> DiagnoseVerdict(
        type = VerdictType.NOT_REPRODUCED,
        victim = diagnosis.victim,
        polluters = emptyList(),
        evidence = "полный prefix не воспроизводит падение: " +
            "${diagnosis.passes}/${diagnosis.attempts} pass",
        reproducedRate = "${diagnosis.attempts - diagnosis.passes}/${diagnosis.attempts}",
        confirmedRate = null,
        orderUnreliable = ctx.orderUnreliable,
        orderUnreliableReason = ctx.orderUnreliableReason,
        forksPossible = ctx.forksPossible,
        markedFlakyInRun = ctx.markedFlakyInRun,
        victimUnstableInIsolation = false,
        reproCommand = null,
        diagnostics = null,
    )

    is Diagnosis.InfraBroken -> DiagnoseVerdict(
        type = VerdictType.INFRA_BROKEN,
        victim = null,
        polluters = emptyList(),
        evidence = "зонд прерван инфра-ошибкой (чинить окружение, не тест)",
        reproducedRate = null,
        confirmedRate = null,
        orderUnreliable = ctx.orderUnreliable,
        orderUnreliableReason = ctx.orderUnreliableReason,
        forksPossible = ctx.forksPossible,
        markedFlakyInRun = false,
        victimUnstableInIsolation = false,
        reproCommand = null,
        diagnostics = diagnosis.cause,
    )

    is Diagnosis.Unsupported -> DiagnoseVerdict(
        type = VerdictType.UNSUPPORTED,
        victim = diagnosis.victim,
        polluters = emptyList(),
        evidence = diagnosis.reason,
        reproducedRate = null,
        confirmedRate = null,
        orderUnreliable = ctx.orderUnreliable,
        orderUnreliableReason = ctx.orderUnreliableReason,
        forksPossible = ctx.forksPossible,
        markedFlakyInRun = ctx.markedFlakyInRun,
        victimUnstableInIsolation = false,
        reproCommand = null,
        diagnostics = diagnosis.reason,
    )
}

private fun isolatedEvidence(ctx: VerdictContext): String = "изолированно ${ctx.repeat}/${ctx.repeat} pass"

/** Polluter entries — FQCN without #method (replay runs the whole class), the victim — FQCN#method. */
private fun reproCommand(
    polluters: List<TestRef>,
    victim: TestRef,
    projectDir: String,
    victimTimeoutSec: Long? = null,
): String {
    check(polluters.isNotEmpty()) { "repro command needs polluters; without them it must be null" }
    return buildString {
        append("flakydiff replay --project ").append(projectDir)
        append(" --prefix ").append(polluters.joinToString(",") { it.testClass })
        append(" --victim ").append(victim.testClass).append('#').append(victim.method)
        // The diagnostic budget is above the replay default — without it the repro is broken (Important
        // v1 review): replay without XML defaults the floor to 30s and kills the repeats of a slow victim.
        victimTimeoutSec?.let { append(" --victim-timeout ").append(it) }
    }
}
