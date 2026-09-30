package io.github.wrinfotel.flakydiff.replay

import org.junit.jupiter.api.Test

/**
 * Fake classes for JvmReplayHarness (Task 2.5): static state, a pollution-dependent
 * victim, and a hanging victim for victim-timeout.
 * The names do not match the surefire include patterns.
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

/**
 * Order-sensitive pair (v1 review, Important): the cleaner must run AFTER the polluter
 * in the recorded order — otherwise it will not remove the pollution. The names are
 * chosen for ClassOrderer.ClassName: alphabetical order (Cleaner < Polluter) is the
 * reverse of the recorded order — the prefix order test forces the collision deliberately.
 */
class FakeOrderCleaner {
    @Test
    fun clean() {
        SharedProbeState.dirty = false
    }
}

class FakeOrderPolluter {
    @Test
    fun pollute() {
        SharedProbeState.dirty = true
    }
}
