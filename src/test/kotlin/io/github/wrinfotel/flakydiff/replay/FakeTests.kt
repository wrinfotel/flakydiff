package io.github.wrinfotel.flakydiff.replay

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Dummy test classes for running ReplayMain in a fresh JVM (Task 2.1).
 * The names must NOT match the surefire include patterns (Test*, *Test, *Tests,
 * *TestCase), or they will start running in the module's own run.
 */
class FakePassing {
    @Test
    fun alwaysPasses() {
        // does nothing
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
        // must not run: BeforeEach fails first
    }
}

class FakeTwoMethods {
    @Test
    fun first() {
        // does nothing
    }

    @Test
    fun second() {
        // does nothing
    }
}

class FakeParamMethod {
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun check(value: Int) {
        // does nothing: only the set of display-name invocations matters
    }
}
