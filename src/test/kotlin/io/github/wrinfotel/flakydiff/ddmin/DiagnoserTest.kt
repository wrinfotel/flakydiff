package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.replay.ReplayResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Шаг 0 ddmin (план Task 3.2, спека §4.3): жертва изолированно (без prefix) —
 * все 4 ветки на детерминированном fake-harness.
 */
class DiagnoserTest {

    private val victim = TestRef("com.example.VictimTest", "flaky")

    @Test
    fun `victim passes isolated - proceed to step 1`() {
        val h = FakeHarness.fromBooleans(listOf(false, false, false), victim)
        assertEquals(Step0Result.Isolated, step0(h, victim))
        // зонд шага 0 — БЕЗ prefix (изоляция), repeat по умолчанию 3
        val call = h.calls.single()
        assertEquals(emptyList<TestRef>(), call.prefix)
        assertEquals(victim, call.victim)
        assertEquals(3, call.repeat)
    }

    @Test
    fun `victim 1 of 3 isolated - NOT_ISOLATED with unstable flag`() {
        // «2/3 прошло» = WEAK → флаг victim_unstable_in_isolation (флаг ⟺ WEAK по построению)
        val h = FakeHarness.fromBooleans(listOf(true, false, false), victim)
        assertEquals(Step0Result.NotIsolated(victimUnstableInIsolation = true), step0(h, victim))
    }

    @Test
    fun `victim 2 of 3 isolated - NOT_ISOLATED without flag`() {
        val h = FakeHarness.fromBooleans(listOf(true, true, false), victim)
        assertEquals(Step0Result.NotIsolated(victimUnstableInIsolation = false), step0(h, victim))
    }

    @Test
    fun `victim 3 of 3 isolated - NOT_ISOLATED without flag`() {
        val h = FakeHarness.fromBooleans(listOf(true, true, true), victim)
        assertEquals(Step0Result.NotIsolated(victimUnstableInIsolation = false), step0(h, victim))
    }

    @Test
    fun `infra at step 0 - INFRA_BROKEN with diagnostics`() {
        val h = FakeHarness { _, _, _ -> ReplayResult.InfraError("classpath broken") }
        assertEquals(Step0Result.InfraBroken("classpath broken"), step0(h, victim))
    }
}

/**
 * Шаг 1 ddmin (план Task 3.3, спека §4.3): полный prefix + жертва обязана падать —
 * иначе NOT_REPRODUCED (вероятны race/время — вне v1).
 */
class Step1Test {

    private val victim = TestRef("com.example.VictimTest", "flaky")
    private val prefix = listOf(
        TestRef("com.example.PolluterTest", "poison"),
        TestRef("com.example.BallastTest", "m1"),
    )

    @Test
    fun `full prefix fails - proceed to ddmin`() {
        val h = FakeHarness { p, v, rep ->
            check(v == victim)
            if (p == prefix) ReplayResult.Reproduced(rep, rep, io.github.wrinfotel.flakydiff.reader.TestFailure("java.lang.AssertionError", "boom", null))
            else ReplayResult.NotReproduced(rep, rep)
        }
        assertEquals(Step1Result.ReproducedWithFullPrefix(failures = 3, attempts = 3), step1(h, prefix, victim))
        // зонд шага 1 — именно полный prefix + жертва
        assertEquals(prefix, h.calls.single().prefix)
        assertEquals(3, h.calls.single().repeat)
    }

    @Test
    fun `prefix does not poison - NOT_REPRODUCED with honest rate`() {
        val h = FakeHarness.fromBooleans(listOf(false, false, false), victim)
        assertEquals(Step1Result.NotReproduced(passes = 3, attempts = 3), step1(h, prefix, victim))
    }

    @Test
    fun `weak signal at step 1 - NOT_REPRODUCED (1 of 3 failed)`() {
        val h = FakeHarness.fromBooleans(listOf(true, false, false), victim)
        assertEquals(Step1Result.NotReproduced(passes = 2, attempts = 3), step1(h, prefix, victim))
    }

    @Test
    fun `infra at step 1 - INFRA_BROKEN`() {
        val h = FakeHarness { _, _, _ -> ReplayResult.InfraError("compilation failed") }
        assertEquals(Step1Result.InfraBroken("compilation failed"), step1(h, prefix, victim))
    }
}

/**
 * Полный пайплайн diagnose (план Task 3.5, спека §4.3): step0 → step1 → ddmin →
 * fresh-process confirmation (repeat=5, критерий сознательно строже ddmin — ≥4 из 5).
 */
class DiagnoseTest {

    private val victim = TestRef("com.example.VictimTest", "flaky")
    private val failure = io.github.wrinfotel.flakydiff.reader.TestFailure("java.lang.AssertionError", "boom", null)

    /** step0 проходит, step1 и ddmin видят polluter, confirmation (repeat=5) задаёт сценарий. */
    private fun harnessWithConfirmation(confirmation: ReplayResult, polluter: TestRef): FakeHarness =
        FakeHarness { p, v, rep ->
            check(v == victim)
            when {
                rep == 5 -> confirmation // единственный вызов с repeat=5 — confirmation
                p.isEmpty() -> ReplayResult.NotReproduced(3, 3) // step0: изолированно проходит
                polluter in p -> ReplayResult.Reproduced(rep, rep, failure)
                else -> ReplayResult.NotReproduced(rep, rep)
            }
        }

    private fun prefixWith(polluter: TestRef, size: Int = 10): List<TestRef> {
        val prefix = (0 until size).map { TestRef("com.example.BallastTest", "m$it") }.toMutableList()
        prefix[4] = polluter
        return prefix
    }

    @Test
    fun `polluter found, confirmed 5 of 5 - ORDER_DEPENDENCY`() {
        val polluter = TestRef("com.example.PolluterTest", "p")
        val h = harnessWithConfirmation(ReplayResult.Reproduced(5, 5, failure), polluter)

        val result = diagnose(h, victim, prefixWith(polluter))

        assertEquals(
            io.github.wrinfotel.flakydiff.ddmin.Diagnosis.OrderDependency(
                victim, listOf(polluter), failure,
                reproducedFailures = 3, reproducedAttempts = 3,
                confirmationFailures = 5, confirmationAttempts = 5,
            ),
            result,
        )
    }

    @Test
    fun `confirmed 4 of 5 - boundary still ORDER_DEPENDENCY`() {
        val polluter = TestRef("com.example.PolluterTest", "p")
        val h = harnessWithConfirmation(ReplayResult.Reproduced(4, 5, failure), polluter)

        val result = diagnose(h, victim, prefixWith(polluter))

        assertEquals(
            io.github.wrinfotel.flakydiff.ddmin.Diagnosis.OrderDependency(
                victim, listOf(polluter), failure,
                reproducedFailures = 3, reproducedAttempts = 3,
                confirmationFailures = 4, confirmationAttempts = 5,
            ),
            result,
        )
    }

    @Test
    fun `confirmation 3 of 5 - UNCONFIRMED with actual rate`() {
        val polluter = TestRef("com.example.PolluterTest", "p")
        val h = harnessWithConfirmation(ReplayResult.Reproduced(3, 5, failure), polluter)

        val result = diagnose(h, victim, prefixWith(polluter))

        assertEquals(
            io.github.wrinfotel.flakydiff.ddmin.Diagnosis.Unconfirmed(
                victim, listOf(polluter),
                reproducedFailures = 3, reproducedAttempts = 3,
                confirmationFailures = 3, confirmationAttempts = 5,
            ),
            result,
        )
    }

    @Test
    fun `confirmation 0 of 5 - UNCONFIRMED with honest rate`() {
        val polluter = TestRef("com.example.PolluterTest", "p")
        val h = harnessWithConfirmation(ReplayResult.NotReproduced(5, 5), polluter)

        val result = diagnose(h, victim, prefixWith(polluter))

        assertEquals(
            io.github.wrinfotel.flakydiff.ddmin.Diagnosis.Unconfirmed(
                victim, listOf(polluter),
                reproducedFailures = 3, reproducedAttempts = 3,
                confirmationFailures = 0, confirmationAttempts = 5,
            ),
            result,
        )
    }

    @Test
    fun `infra at confirmation - INFRA_BROKEN, not UNCONFIRMED`() {
        val polluter = TestRef("com.example.PolluterTest", "p")
        val h = harnessWithConfirmation(ReplayResult.InfraError("probe timeout"), polluter)

        val result = diagnose(h, victim, prefixWith(polluter))

        assertEquals(io.github.wrinfotel.flakydiff.ddmin.Diagnosis.InfraBroken("probe timeout"), result)
    }

    @Test
    fun `parameterized victim - UNSUPPORTED before any probe`() {
        // display-name invocation из recorded XML (параметризованный тест):
        // v1 не умеет повторять отдельный invocation — честный отказ ДО зондов
        val paramVictim = TestRef("com.example.ParamTest", "check(int)[1]")
        val h = FakeHarness { _, _, _ -> error("probe must not run for UNSUPPORTED") }

        val d = diagnose(h, paramVictim, listOf(TestRef("com.example.PolluterTest", "p")))

        assertTrue(d is Diagnosis.Unsupported, "got: $d")
        d as Diagnosis.Unsupported
        assertEquals(paramVictim, d.victim)
        assertTrue(d.reason.contains("check(int)[1]"), "имя теста в диагностике: ${d.reason}")
        assertTrue(d.reason.contains("не адресуется напрямую"), "reason: ${d.reason}")
        assertTrue(h.calls.isEmpty(), "ни одного зонда до вердикта: ${h.calls}")
    }
}
