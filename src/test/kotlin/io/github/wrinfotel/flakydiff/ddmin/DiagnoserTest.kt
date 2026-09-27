package io.github.wrinfotel.flakydiff.ddmin

import io.github.wrinfotel.flakydiff.reader.TestRef
import io.github.wrinfotel.flakydiff.replay.ReplayResult
import org.junit.jupiter.api.Assertions.assertEquals
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
