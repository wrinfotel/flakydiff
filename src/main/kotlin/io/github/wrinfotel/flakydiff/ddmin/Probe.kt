package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.replay.ReplayResult

/**
 * Бакеты предиката ddmin (спека §4.3). Считаем УПАВШИЕ повторы жертвы:
 * - FAILS — упало ≥ threshold из attempts (дефолт ≥2/3);
 * - PASSES — не упало ни одного (полный rate);
 * - WEAK — упало, но меньше threshold (по умолчанию ровно 1 из 3) — слабый сигнал,
 *   консервативно не сужает ddmin; отдельный исход, НЕ приравнивается к PASSES/FAILS;
 * - INFRA — InfraError (classpath/подготовка/таймаут): не фейл теста, политика §4.3.
 */
enum class ProbeOutcome { FAILS, PASSES, WEAK, INFRA }

/**
 * Свёртка ReplayResult в бакет. Threshold настраивается флагом --min-fail-ratio
 * (дефолт 2 из 3): критерий «≥1 из 3» вместе с шумным предикатом ломает
 * монотонность ddmin — спонтанные срабатывания дают ложные сужения.
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
    // не прошедший повтор (упал или скипнулся) — консервативно не «прошёл»
    else -> ProbeOutcome.WEAK
}
