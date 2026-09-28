package io.github.wrinfotel.flakydiff.verdict

import io.github.wrinfotel.flakydiff.ddmin.Diagnosis
import io.github.wrinfotel.flakydiff.reader.TestFailure
import io.github.wrinfotel.flakydiff.reader.TestRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 5.1 (план): сборка вердикта из результатов этапа 3. Контракт repro-команды:
 * polluter-записи — FQCN БЕЗ #method (replay запускает весь класс, форма допущена
 * контрактом Task 2.1), жертва — FQCN#method. Без polluters (NOT_ISOLATED,
 * NOT_REPRODUCED, INFRA_BROKEN, UNSUPPORTED) reproCommand = null — воспроизводить
 * нечем, честно не выдаём команду, которая заведомо не сработает.
 */
class VerdictTest {

    private val victim = TestRef("com.example.VictimTest", "flaky")
    private val polluter = TestRef("com.example.PolluterTest", "pollutes")
    private val failure = TestFailure("org.opentest4j.AssertionFailedError", "field not clean", null)

    /** Базовый контекст: порядок надёжен, форки возможны, жертва в XML не помечена flaky. */
    private fun ctx(
        projectDir: String = "path/to/project",
        orderUnreliable: Boolean = false,
        orderUnreliableReason: String? = null,
        forksPossible: Boolean = true,
        markedFlakyInRun: Boolean = false,
    ) = VerdictContext(
        projectDir = projectDir,
        repeat = 3,
        orderUnreliable = orderUnreliable,
        orderUnreliableReason = orderUnreliableReason,
        forksPossible = forksPossible,
        markedFlakyInRun = markedFlakyInRun,
    )

    @Test
    fun `order dependency - full mapping with repro command`() {
        val d = Diagnosis.OrderDependency(
            victim, listOf(polluter), failure,
            reproducedFailures = 3, reproducedAttempts = 3,
            confirmationFailures = 5, confirmationAttempts = 5,
        )

        val v = buildVerdict(d, ctx())

        assertEquals(VerdictType.ORDER_DEPENDENCY, v.type)
        assertEquals(victim, v.victim)
        assertEquals(listOf(polluter), v.polluters)
        assertEquals("изолированно 3/3 pass; после polluter 3/3 fail", v.evidence)
        assertEquals("3/3", v.reproducedRate)
        assertEquals("5/5", v.confirmedRate)
        assertFalse(v.orderUnreliable)
        assertNull(v.orderUnreliableReason)
        assertTrue(v.forksPossible)
        assertFalse(v.markedFlakyInRun)
        assertFalse(v.victimUnstableInIsolation)
        assertNull(v.diagnostics)
        // polluter — FQCN без #method, жертва — FQCN#method
        assertEquals(
            "flakydiff replay --project path/to/project " +
                "--prefix com.example.PolluterTest " +
                "--victim com.example.VictimTest#flaky",
            v.reproCommand,
        )
    }

    @Test
    fun `order dependency - two polluters joined by comma, fqcn only`() {
        val p1 = TestRef("com.example.PolluterOneTest", "poisonOne")
        val p2 = TestRef("com.example.PolluterTwoTest", "poisonTwo")
        val d = Diagnosis.OrderDependency(
            victim, listOf(p1, p2), failure,
            reproducedFailures = 2, reproducedAttempts = 3,
            confirmationFailures = 4, confirmationAttempts = 5,
        )

        val v = buildVerdict(d, ctx())

        assertEquals(
            "flakydiff replay --project path/to/project " +
                "--prefix com.example.PolluterOneTest,com.example.PolluterTwoTest " +
                "--victim com.example.VictimTest#flaky",
            v.reproCommand,
        )
        assertEquals("2/3", v.reproducedRate)
        assertEquals("4/5", v.confirmedRate)
        assertEquals("изолированно 3/3 pass; после polluter 2/3 fail", v.evidence)
    }

    @Test
    fun `unconfirmed - honest confirmed rate, repro command still present`() {
        val d = Diagnosis.Unconfirmed(
            victim, listOf(polluter),
            reproducedFailures = 2, reproducedAttempts = 3,
            confirmationFailures = 3, confirmationAttempts = 5,
        )

        val v = buildVerdict(d, ctx())

        assertEquals(VerdictType.UNCONFIRMED, v.type)
        assertEquals(victim, v.victim)
        assertEquals("3/5", v.confirmedRate)
        assertEquals("2/3", v.reproducedRate)
        assertTrue(v.reproCommand != null, "polluters есть — repro-команда должна быть")
    }

    @Test
    fun `not isolated with unstable flag - no repro command`() {
        val v = buildVerdict(Diagnosis.NotIsolated(victim, victimUnstableInIsolation = true), ctx())

        assertEquals(VerdictType.NOT_ISOLATED, v.type)
        assertEquals(victim, v.victim)
        assertTrue(v.victimUnstableInIsolation, "флаг victim_unstable_in_isolation при WEAK-бакете")
        assertEquals(emptyList<TestRef>(), v.polluters)
        assertNull(v.reproCommand)
        assertNull(v.reproducedRate)
        assertNull(v.confirmedRate)
        assertEquals("жертва флейкует изолированно (без prefix)", v.evidence)
    }

    @Test
    fun `not isolated without flag - simply fails alone`() {
        val v = buildVerdict(Diagnosis.NotIsolated(victim, victimUnstableInIsolation = false), ctx())

        assertEquals(VerdictType.NOT_ISOLATED, v.type)
        assertFalse(v.victimUnstableInIsolation)
        assertEquals("жертва падает изолированно (без prefix)", v.evidence)
        assertNull(v.reproCommand)
    }

    @Test
    fun `not reproduced - honest failure rate of the probe, no repro`() {
        val v = buildVerdict(Diagnosis.NotReproduced(victim, passes = 3, attempts = 3), ctx())

        assertEquals(VerdictType.NOT_REPRODUCED, v.type)
        assertEquals("0/3", v.reproducedRate)
        assertEquals("полный prefix не воспроизводит падение: 3/3 pass", v.evidence)
        assertEquals(emptyList<TestRef>(), v.polluters)
        assertNull(v.reproCommand)
        assertNull(v.confirmedRate)
    }

    @Test
    fun `not reproduced weak signal - 1 of 3 failures is honest rate`() {
        val v = buildVerdict(Diagnosis.NotReproduced(victim, passes = 2, attempts = 3), ctx())

        assertEquals("1/3", v.reproducedRate)
        assertEquals("полный prefix не воспроизводит падение: 2/3 pass", v.evidence)
    }

    @Test
    fun `infra broken - diagnostics carry cause, no victim and no repro`() {
        val v = buildVerdict(Diagnosis.InfraBroken("classpath broken: no com.acme.Foo"), ctx())

        assertEquals(VerdictType.INFRA_BROKEN, v.type)
        assertNull(v.victim)
        assertEquals(emptyList<TestRef>(), v.polluters)
        assertNull(v.reproCommand)
        assertNull(v.reproducedRate)
        assertNull(v.confirmedRate)
        assertEquals("classpath broken: no com.acme.Foo", v.diagnostics)
        assertTrue(v.evidence.isNotBlank(), "evidence не пуст: ${v.evidence}")
    }

    @Test
    fun `unsupported - reason in evidence and diagnostics, no repro`() {
        val reason = "check(int)[1] не адресуется напрямую, fallback не сработал: " +
            "параметризованный тест нельзя повторить поштучно в свежей JVM"
        val v = buildVerdict(Diagnosis.Unsupported(victim, reason), ctx())

        assertEquals(VerdictType.UNSUPPORTED, v.type)
        assertEquals(victim, v.victim)
        assertEquals(reason, v.evidence)
        assertEquals(reason, v.diagnostics)
        assertEquals(emptyList<TestRef>(), v.polluters)
        assertNull(v.reproCommand)
        assertNull(v.reproducedRate)
    }

    @Test
    fun `flags pass through from context`() {
        val d = Diagnosis.OrderDependency(
            victim, listOf(polluter), failure,
            reproducedFailures = 3, reproducedAttempts = 3,
            confirmationFailures = 5, confirmationAttempts = 5,
        )

        val v = buildVerdict(
            d,
            ctx(
                orderUnreliable = true,
                orderUnreliableReason = "mtime соседних классов совпадают",
                forksPossible = false,
                markedFlakyInRun = true,
            ),
        )

        assertTrue(v.orderUnreliable)
        assertEquals("mtime соседних классов совпадают", v.orderUnreliableReason)
        assertFalse(v.forksPossible)
        assertTrue(v.markedFlakyInRun)
    }

    @Test
    fun `different diagnosis types never collide in verdict type`() {
        val types = listOf(
            buildVerdict(
                Diagnosis.OrderDependency(
                    victim, listOf(polluter), failure,
                    reproducedFailures = 3, reproducedAttempts = 3,
                    confirmationFailures = 5, confirmationAttempts = 5,
                ),
                ctx(),
            ).type,
            buildVerdict(Diagnosis.Unconfirmed(victim, listOf(polluter), 3, 3, 3, 5), ctx()).type,
            buildVerdict(Diagnosis.NotIsolated(victim, true), ctx()).type,
            buildVerdict(Diagnosis.NotReproduced(victim, 0, 3), ctx()).type,
            buildVerdict(Diagnosis.InfraBroken("x"), ctx()).type,
            buildVerdict(Diagnosis.Unsupported(victim, "r"), ctx()).type,
        )
        assertEquals(6, types.toSet().size, "все шесть типов различны: $types")
        assertNotEquals(VerdictType.ORDER_DEPENDENCY, VerdictType.UNCONFIRMED)
    }
}
