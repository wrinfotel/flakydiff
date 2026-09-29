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

/**
 * Порядочно-чувствительная пара (ревью v1, Important): cleaner обязан исполняться
 * ПОСЛЕ polluter'а в записанном порядке — иначе не снимет загрязнение. Имена
 * подобраны под ClassOrderer.ClassName: алфавитный порядок (Cleaner < Polluter)
 * обратен порядку записи — тест порядка prefix форсирует коллизию осознанно.
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
