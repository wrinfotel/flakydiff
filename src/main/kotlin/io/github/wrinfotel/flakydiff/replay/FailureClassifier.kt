package io.github.wrinfotel.flakydiff.replay

/**
 * Классификация результата повтора жертвы по правилам спеки §4.2. Живёт ТОЛЬКО
 * на CLI-стороне: ReplayMain отдаёт сырые stage+тип исключения в JSON, в
 * replay-jar классификатор не входит.
 */
enum class FailureKind { TEST_FAILURE, INFRA_ERROR }

object FailureClassifier {

    private val infraStages = setOf("BEFORE_ALL", "BEFORE_EACH", "ENGINE")

    private val classpathErrorTypes = setOf(
        "java.lang.NoClassDefFoundError",
        "java.lang.ClassNotFoundException",
    )

    fun classify(stage: String?, failureType: String?): FailureKind {
        // Сломанный classpath — инфраструктура даже если вылетел из тела теста.
        if (failureType in classpathErrorTypes) return FailureKind.INFRA_ERROR
        // Watchdog ReplayMain: повтор жертвы превысил victim-timeout (спека §4.2).
        if (failureType == "flakydiff.victim-timeout") return FailureKind.INFRA_ERROR
        // Ошибка подготовки (@BeforeAll/@BeforeEach/движок) — не фейл теста,
        // независимо от того, какая фаза упала.
        if (stage in infraStages) return FailureKind.INFRA_ERROR
        return FailureKind.TEST_FAILURE
    }
}
