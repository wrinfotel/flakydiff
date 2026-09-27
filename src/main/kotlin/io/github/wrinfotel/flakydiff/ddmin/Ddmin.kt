package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.replay.ReplayHarness
import io.github.wrinfotel.flakydiff.replay.ReplayResult

/** Результат ddmin (план Task 3.4, спека §4.3). */
sealed interface DdminResult {
    /**
     * 1-minimal (или плато) множество предшественников в записанном порядке.
     * [lastFailure] — фейл последнего FAIL-зонда (для диагностики вердикта).
     */
    data class Minimized(val polluters: List<TestRef>, val lastFailure: TestFailure?) : DdminResult

    /**
     * Два InfraError подряд на одном зонде. Результат ddmin ОТБРАСЫВАЕТСЯ:
     * вердикт не содержит ни polluters, ни repro-команды — только диагностику
     * («чинить окружение, не тест»); сужение по инфра-фейлу не производится.
     */
    data class InfraBroken(val cause: String) : DdminResult
}

private sealed interface ProbeAttempt {
    data class Completed(val outcome: ProbeOutcome, val failure: TestFailure?) : ProbeAttempt
    data class Broken(val cause: String) : ProbeAttempt
}

/**
 * ddmin по чанкам: классический delta debugging по множеству предшественников.
 * Адаптация: элементы нельзя переупорядочивать — чанки берутся подряд в записанном
 * порядке, комплементы сохраняют порядок (проверяется тестом).
 *
 * 1. n = 2; разбить prefix на n подряд идущих чанков.
 * 2. Зонд «prefix минус чанк»: FAILS → prefix := prefix минус чанк, n = max(2, n-1).
 * 3. Полный проход без сужения → n удваивается; когда чанки уже по одному элементу
 *    (n >= размера) и сужения нет — стоп: текущее множество = результат (плато).
 * 4. Одиночный элемент: зонд FAILS → single polluter. PASSES/WEAK — плато
 *    (множество минимально по построению; честность обеспечивает confirmation).
 * 5. Политика §4.3: InfraError на зонде → один повтор того же зонда; повторный
 *    InfraError → INFRA_BROKEN. INFRA никогда не сужает множество.
 */
fun ddmin(
    harness: ReplayHarness,
    prefix: List<TestRef>,
    victim: TestRef,
    repeat: Int = 3,
    threshold: Int = 2,
): DdminResult {
    // Шаг 1 уже установил воспроизведение на полном prefix — пустым он прийти не может
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
                    // PASSES: чанк виноват — оставляем. WEAK: слабый сигнал консервативно
                    // НЕ сужает (спека §4.3) — явная ветка, не приравнивается ни к PASSES, ни к FAILS.
                    ProbeOutcome.PASSES, ProbeOutcome.WEAK -> {}
                    ProbeOutcome.INFRA -> error("unreachable: retry wrapper resolves INFRA")
                }
            }
        }
        if (removed) continue

        // Полный проход без сужения: плато при чанках по одному элементу, иначе удвоение
        if (n >= current.size) return DdminResult.Minimized(current, lastFailure)
        n = minOf(2 * n, current.size)
    }
}

/**
 * Зонд с политикой §4.3: первый InfraError — один повтор ТОГО ЖЕ зонда;
 * повторный InfraError → Broken (INFRA_BROKEN у вызывающего). Успешный ретрай
 * (FAILS/PASSES/WEAK) продолжает ddmin как ни в чём не бывало.
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
 * Подряд идущие чанки в записанном порядке: count = min(n, size) отрезков,
 * остаток распределяется по первым чанкам (base+1). Чанки покрывают весь список,
 * каждый непуст — сужение строго уменьшает множество.
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
