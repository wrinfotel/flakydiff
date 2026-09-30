package io.github.wrinfotel.flakydiff.verdict

import kotlinx.serialization.json.Json

/** Type name for text/the §5 checklist: underscores → hyphens (ORDER_DEPENDENCY → ORDER-DEPENDENCY). */
fun VerdictType.display(): String = name.replace('_', '-')

/**
 * The flags line of §4.4: order_unreliable and forks_possible — always (spec);
 * victim_unstable_in_isolation and marked_flaky_in_run — only when set.
 * The reason the order is unreliable goes in parentheses after order_unreliable=yes.
 */
private fun flagsLine(v: DiagnoseVerdict): String {
    val orderUnreliable = when {
        !v.orderUnreliable -> "order_unreliable=no"
        v.orderUnreliableReason != null -> "order_unreliable=yes (${v.orderUnreliableReason})"
        else -> "order_unreliable=yes"
    }
    return buildList {
        add(orderUnreliable)
        add(if (v.forksPossible) "forks_possible=yes" else "forks_possible=no")
        if (v.victimUnstableInIsolation) add("victim_unstable_in_isolation=yes")
        if (v.markedFlakyInRun) add("marked_flaky_in_run=yes")
    }.joinToString("  ")
}

/**
 * Text rendering of §4.4: the VERDICT header (+ rates when present), the
 * victim/polluter lines (one per polluter), evidence, diagnostics
 * (only if it does not duplicate the evidence), flags, repro. Values are aligned to
 * the 10th column, as in the spec examples; lines with no value (repro when null)
 * are not printed.
 */
fun renderText(v: DiagnoseVerdict): String {
    val lines = mutableListOf<String>()
    fun line(label: String, value: String) = lines.add(label.padEnd(9) + " " + value)

    val rates = buildList {
        v.reproducedRate?.let { add("reproduced $it") }
        v.confirmedRate?.let { add("confirmed fresh-process $it") }
    }
    lines.add(
        buildString {
            append("VERDICT: ").append(v.type.display())
            if (rates.isNotEmpty()) append(" (").append(rates.joinToString(", ")).append(')')
        },
    )
    v.victim?.let { line("victim:", "${it.testClass}#${it.method}") }
    v.polluters.forEach { line("polluter:", "${it.testClass}#${it.method}") }
    line("evidence:", v.evidence)
    v.diagnostics?.takeIf { it != v.evidence }?.let { line("diagnostics:", it) }
    line("flags:", flagsLine(v))
    v.reproCommand?.let { line("repro:", it) }
    return lines.joinToString("\n")
}

private val verdictJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

/** The §4.4 JSON: the same camelCase fields, the type with underscores (name registry Task 5.1). */
fun renderJson(v: DiagnoseVerdict): String = verdictJson.encodeToString(DiagnoseVerdict.serializer(), v)

/** Reverse render for `flakydiff report --verdict file.json` (Task 5.3). */
fun parseVerdictJson(text: String): DiagnoseVerdict = verdictJson.decodeFromString(DiagnoseVerdict.serializer(), text)
