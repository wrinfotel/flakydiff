package io.github.wrinfotel.flakydiff.replay

import org.junit.jupiter.api.Test

/**
 * Фейковые классы для JvmReplayHarness (Task 2.5): статическое состояние,
 * жертва, зависящая от загрязнения, и зависающая жертва для victim-timeout.
 * Имена не попадают под include-паттерны surefire.
 */
object SharedProbeState {
    @JvmStatic
    var dirty: Boolean = false
}

class FakeStatePolluter {
    @Test
    fun makeDirty() {
        SharedProbeState.dirty = true
    }
}

class FakeStatefulVictim {
    @Test
    fun failsWhenDirty() {
        if (SharedProbeState.dirty) {
            throw AssertionError("polluted")
        }
    }
}

class FakeHangingVictim {
    @Test
    fun hangs() {
        Thread.sleep(8_000)
    }
}
