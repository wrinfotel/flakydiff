package io.github.wrinfotel.flakydiff.verdict

import kotlinx.serialization.json.Json

/** Имя типа для текста/чек-листа §5: подчёркивания → дефисы (ORDER_DEPENDENCY → ORDER-DEPENDENCY). */
fun VerdictType.display(): String = name.replace('_', '-')

/**
 * Строка flags §4.4: order_unreliable и forks_possible — всегда (спека);
 * victim_unstable_in_isolation и marked_flaky_in_run — только когда выставлены.
 * Причина недостоверности порядка — в скобках у order_unreliable=yes.
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
 * Текстовый рендер §4.4: заголовок VERDICT (+ rates, когда есть), строки
 * victim/polluter (по строке на каждого polluter'а), evidence, diagnostics
 * (только если не дублирует evidence), flags, repro. Значения выровнены на
 * 10-ю колонку, как в примерах спеки; строки без значения (repro при null) —
 * не печатаются.
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

/** JSON §4.4: те же поля camelCase, тип — подчёркивания (реестр имён Task 5.1). */
fun renderJson(v: DiagnoseVerdict): String = verdictJson.encodeToString(DiagnoseVerdict.serializer(), v)

/** Обратный рендер для `flakydiff report --verdict file.json` (Task 5.3). */
fun parseVerdictJson(text: String): DiagnoseVerdict = verdictJson.decodeFromString(DiagnoseVerdict.serializer(), text)
