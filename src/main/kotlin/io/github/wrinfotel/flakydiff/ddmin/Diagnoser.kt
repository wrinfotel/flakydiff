package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestFailure
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

/** Результат шага 1 (спека §4.3): полный prefix + жертва обязан падать. */
sealed interface Step1Result {
    /** Полный prefix воспроизводит падение — идём в ddmin. */
    data object ReproducedWithFullPrefix : Step1Result

    /** Полный prefix НЕ воспроизводит (включая слабый сигнал) — вероятны race/время, вне v1. */
    data class NotReproduced(val passes: Int, val attempts: Int) : Step1Result

    /** Инфра сломана — чинить окружение, не тест. */
    data class InfraBroken(val cause: String) : Step1Result
}

/**
 * Шаг 1: полный prefix + жертва обязана падать (по критерию бакетов). PASSES и WEAK
 * консервативно ведут в NOT_REPRODUCED — честный rate сохраняется для вердикта.
 */
fun step1(harness: ReplayHarness, prefix: List<TestRef>, victim: TestRef, repeat: Int = 3): Step1Result {
    val result = harness.replay(prefix, victim, repeat)
    return when (val outcome = classify(result)) {
        ProbeOutcome.FAILS -> Step1Result.ReproducedWithFullPrefix
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
 * Итог диагностики (спека §4.3–4.4): имена в коде/JSON — с подчёркиваниями,
 * в чек-листе §5 — те же через дефис (NOT_ISOLATED = NOT-ISOLATED).
 */
sealed interface Diagnosis {
    /** Порядок-dep найден и подтверждён свежей JVM: ≥4 из 5 повторов жертвы упали. */
    data class OrderDependency(
        val victim: TestRef,
        val polluters: List<TestRef>,
        val lastFailure: TestFailure?,
        val confirmationFailures: Int,
        val confirmationAttempts: Int,
    ) : Diagnosis

    /** ddmin нашёл множество, но confirmation не дотянул до ≥4/5 — честный rate фиксируем. */
    data class Unconfirmed(
        val victim: TestRef,
        val polluters: List<TestRef>,
        val confirmationFailures: Int,
        val confirmationAttempts: Int,
    ) : Diagnosis

    /** Шаг 0: жертва падает/флейкует сама по себе — не order-dependency. */
    data class NotIsolated(val victim: TestRef, val victimUnstableInIsolation: Boolean) : Diagnosis

    /** Шаг 1: полный prefix не воспроизводит падение — вероятны race/время (вне v1). */
    data class NotReproduced(val victim: TestRef, val passes: Int, val attempts: Int) : Diagnosis

    /** Инфра сломана (единая политика §4.3): чинить окружение, не тест; сужений не показываем. */
    data class InfraBroken(val cause: String) : Diagnosis

    /**
     * Форма жертвы, которую v1 не диагностирует (план Task 4.3): display-name
     * invocation параметризованного теста (check(int)[1]). Фолбэк «класс +
     * префикс имени» гоняет все invocations разом и не изолирует отдельный
     * повтор — честный отказ с именем теста, БЕЗ зондов и без сужений.
     */
    data class Unsupported(val victim: TestRef, val reason: String) : Diagnosis
}

/** Confirmation — отдельный зонд в свежей JVM, критерий сознательно строже ddmin: ≥4 из 5 (80% против 67%). */
private const val CONFIRM_REPEAT = 5
private const val CONFIRM_MIN_FAILURES = 4

/**
 * Полный пайплайн: шаг 0 (изоляция) → шаг 1 (полный prefix) → ddmin →
 * fresh-process confirmation. Каждая ступень честно может остановить диагноз.
 */
fun diagnose(
    harness: ReplayHarness,
    victim: TestRef,
    prefix: List<TestRef>,
    repeat: Int = 3,
    threshold: Int = 2,
): Diagnosis {
    // display-name жертвы (параметризованный тест) — до любых зондов: повтор
    // отдельного invocation в свежей JVM невозможен, зондить такое — мусор.
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

    when (val s1 = step1(harness, prefix, victim, repeat)) {
        is Step1Result.NotReproduced -> return Diagnosis.NotReproduced(victim, s1.passes, s1.attempts)
        is Step1Result.InfraBroken -> return Diagnosis.InfraBroken(s1.cause)
        Step1Result.ReproducedWithFullPrefix -> {}
    }

    val minimal = when (val d = ddmin(harness, prefix, victim, repeat, threshold)) {
        is DdminResult.InfraBroken -> return Diagnosis.InfraBroken(d.cause)
        is DdminResult.Minimized -> d
    }

    val confirmation = harness.replay(minimal.polluters, victim, CONFIRM_REPEAT)
    val failures = when (confirmation) {
        is ReplayResult.Reproduced -> confirmation.failures
        is ReplayResult.NotReproduced -> confirmation.attempts - confirmation.passes
        // Единая политика §4.3: InfraError ≠ фейл теста — INFRA_BROKEN, не UNCONFIRMED
        is ReplayResult.InfraError -> return Diagnosis.InfraBroken(confirmation.cause)
    }
    return if (failures >= CONFIRM_MIN_FAILURES) {
        Diagnosis.OrderDependency(victim, minimal.polluters, minimal.lastFailure, failures, CONFIRM_REPEAT)
    } else {
        Diagnosis.Unconfirmed(victim, minimal.polluters, failures, CONFIRM_REPEAT)
    }
}
