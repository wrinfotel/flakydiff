package io.github.wrinfotel.flakydiff.replay

/**
 * Classification of the victim-repeat result per the §4.2 spec rules. Lives ONLY
 * on the CLI side: ReplayMain emits the raw stage+exception type into JSON; the
 * replay-jar does not include the classifier.
 */
enum class FailureKind { TEST_FAILURE, INFRA_ERROR }

object FailureClassifier {

    private val infraStages = setOf("BEFORE_ALL", "BEFORE_EACH", "ENGINE")

    private val classpathErrorTypes = setOf(
        "java.lang.NoClassDefFoundError",
        "java.lang.ClassNotFoundException",
    )

    fun classify(stage: String?, failureType: String?): FailureKind {
        // A broken classpath is infrastructure even if it blew up from the test body.
        if (failureType in classpathErrorTypes) return FailureKind.INFRA_ERROR
        // ReplayMain watchdog: the victim repeat exceeded victim-timeout (spec §4.2).
        if (failureType == "flakydiff.victim-timeout") return FailureKind.INFRA_ERROR
        // A preparation error (@BeforeAll/@BeforeEach/engine) is not a test failure,
        // regardless of which phase failed.
        if (stage in infraStages) return FailureKind.INFRA_ERROR
        return FailureKind.TEST_FAILURE
    }
}
