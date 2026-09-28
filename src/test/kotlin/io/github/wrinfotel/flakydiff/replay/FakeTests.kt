package io.github.wrinfotel.flakydiff.replay

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Фиктивные тестовые классы для запуска ReplayMain в свежей JVM (Task 2.1).
 * Имена НЕ должны попадать под include-паттерны surefire (Test*, *Test, *Tests,
 * *TestCase), иначе они начнут выполняться в собственном прогоне модуля.
 */
class FakePassing {
    @Test
    fun alwaysPasses() {
        // ничего не делает
    }
}

class FakeFailing {
    @Test
    fun alwaysFails() {
        throw AssertionError("boom")
    }
}

class FakeBeforeEachFails {
    @BeforeEach
    fun setUp() {
        throw IllegalStateException("boom-in-before-each")
    }

    @Test
    fun neverRuns() {
        // не должен выполниться: BeforeEach падает раньше
    }
}

class FakeTwoMethods {
    @Test
    fun first() {
        // ничего не делает
    }

    @Test
    fun second() {
        // ничего не делает
    }
}

class FakeParamMethod {
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun check(value: Int) {
        // ничего не делает: важен сам набор display-name invocations
    }
}
