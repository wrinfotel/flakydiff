package io.github.wrinfotel.flakydiff.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Правила спеки §4.2 (блокирующие): BEFORE_* и ENGINE — всегда InfraError;
 * NoClassDefFoundError / ClassNotFoundException — InfraError из любой стадии
 * (сломанный classpath, не логика); остальное из stage=TEST — TestFailure.
 */
class FailureClassifierTest {

    companion object {
        @JvmStatic
        fun cases() = listOf(
            arrayOf("TEST", "java.lang.AssertionError", FailureKind.TEST_FAILURE),
            arrayOf("TEST", "java.lang.IllegalStateException", FailureKind.TEST_FAILURE),
            arrayOf("TEST", null as String?, FailureKind.TEST_FAILURE),
            arrayOf("BEFORE_EACH", "java.lang.IllegalStateException", FailureKind.INFRA_ERROR),
            arrayOf("BEFORE_ALL", "java.lang.RuntimeException", FailureKind.INFRA_ERROR),
            arrayOf("ENGINE", "org.junit.platform.JUnitException", FailureKind.INFRA_ERROR),
            arrayOf("TEST", "java.lang.NoClassDefFoundError", FailureKind.INFRA_ERROR),
            arrayOf("TEST", "java.lang.ClassNotFoundException", FailureKind.INFRA_ERROR),
            // маркер watchdog'а ReplayMain (спека: victim-timeout -> InfraError):
            arrayOf("TEST", "flakydiff.victim-timeout", FailureKind.INFRA_ERROR),
            // из любой стадии, независимо от BEFORE-правила:
            arrayOf("BEFORE_EACH", "java.lang.NoClassDefFoundError", FailureKind.INFRA_ERROR),
            arrayOf("ENGINE", "java.lang.ClassNotFoundException", FailureKind.INFRA_ERROR),
            // стадия неизвестна — решает тип:
            arrayOf(null as String?, "java.lang.NoClassDefFoundError", FailureKind.INFRA_ERROR),
            arrayOf(null as String?, "java.lang.AssertionError", FailureKind.TEST_FAILURE),
        )
    }

    @ParameterizedTest(name = "stage={0}, type={1} -> {2}")
    @MethodSource("cases")
    fun classify(stage: String?, failureType: String?, expected: FailureKind) {
        assertEquals(expected, FailureClassifier.classify(stage, failureType))
    }
}
