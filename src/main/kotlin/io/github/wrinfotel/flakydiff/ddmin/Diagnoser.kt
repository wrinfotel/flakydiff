package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.replay.ReplayHarness
import io.github.wrinfotel.flakydiff.replay.ReplayResult

/**
 * Результат шага 0 (спека §4.3): жертва изолированно, БЕЗ prefix.
 * Любой NOT_ISOLATED — не order-dependency: идти в другую гипотезу.
 */
sealed interface Step0Result {
    /** Повторы жертвы все прошли — порядок-dep возможен, идём к шагу 1. */
    data object Isolated : Step0Result

    /**
     * Тест падает/флейкует сам по себе. [victimUnstableInIsolation] — флаг
     * victim_unstable_in_isolation: true ⟺ бакет WEAK («2/3 прошло» — подозрение
     * на флейк самого теста); false — бакет FAILS (тест просто падает сам).
     */
    data class NotIsolated(val victimUnstableInIsolation: Boolean) : Step0Result

    /** Инфра сломана — чинить окружение, не тест (диагностика прилагается). */
    data class InfraBroken(val cause: String) : Step0Result
}

/**
 * Шаг 0: жертва изолированно (без prefix) проходит repeat/repeat — иначе в ddmin не идём.
 */
fun step0(harness: ReplayHarness, victim: TestRef, repeat: Int = 3): Step0Result {
    val result = harness.replay(emptyList(), victim, repeat)
    return when (val outcome = classify(result)) {
        ProbeOutcome.PASSES -> Step0Result.Isolated
        // флаг ⟺ WEAK по построению (план Task 3.2): отдельный WEAK_FLAKY не нужен
        ProbeOutcome.WEAK -> Step0Result.NotIsolated(victimUnstableInIsolation = true)
        ProbeOutcome.FAILS -> Step0Result.NotIsolated(victimUnstableInIsolation = false)
        ProbeOutcome.INFRA -> Step0Result.InfraBroken((result as ReplayResult.InfraError).cause)
    }
}
