package io.github.wrinfotel.flakydiff.verdict

import io.github.wrinfotel.flakydiff.ddmin.Diagnosis
import io.github.wrinfotel.flakydiff.reader.TestRef
import kotlinx.serialization.Serializable

/**
 * Реестр имён (план Task 5.1): в коде/JSON — подчёркивания, в чек-листе §5 спеки —
 * те же через дефис (NOT_ISOLATED = NOT-ISOLATED): это отображение одного и того же.
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
    val reproducedRate: String?,   // "3/3" — rate шага 1 (полный prefix); null, если зонда не было
    val confirmedRate: String?,    // "5/5" | null, если confirmation не дошёл
    val orderUnreliable: Boolean,
    val orderUnreliableReason: String?,
    val forksPossible: Boolean,
    val markedFlakyInRun: Boolean,
    val victimUnstableInIsolation: Boolean,
    // null, если polluters нет: INFRA_BROKEN, NOT_REPRODUCED, NOT_ISOLATED, UNSUPPORTED
    val reproCommand: String?,
    val diagnostics: String?,      // для UNSUPPORTED/INFRA_BROKEN
)

/**
 * Факты, которых в [Diagnosis] нет — они этажом выше (reader/CLI). [repeat] —
 * повторов шага 0: Isolated ⟺ все repeat прошли, поэтому «изолированно N/N pass».
 */
data class VerdictContext(
    val projectDir: String,
    val repeat: Int,
    val orderUnreliable: Boolean,
    val orderUnreliableReason: String? = null,
    val forksPossible: Boolean = true,
    val markedFlakyInRun: Boolean = false,
)

/**
 * Маппинг результата этапа 3 в вердикт §4.4. Каждый тип Diagnosis отображается
 * однозначно; числа (rates) переносятся дословно — «молча не врём».
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
        reproCommand = reproCommand(diagnosis.polluters, diagnosis.victim, ctx.projectDir),
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
        reproCommand = reproCommand(diagnosis.polluters, diagnosis.victim, ctx.projectDir),
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

/** Polluter-записи — FQCN без #method (replay запускает весь класс), жертва — FQCN#method. */
private fun reproCommand(polluters: List<TestRef>, victim: TestRef, projectDir: String): String {
    check(polluters.isNotEmpty()) { "repro command needs polluters; without them it must be null" }
    return "flakydiff replay --project $projectDir " +
        "--prefix ${polluters.joinToString(",") { it.testClass }} " +
        "--victim ${victim.testClass}#${victim.method}"
}
