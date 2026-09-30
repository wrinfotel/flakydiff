package io.github.wrinfotel.flakydiff.fixture

import java.nio.file.Files
import java.nio.file.Path

/**
 * Fixture-project scenarios (plan Task 4.1, spec §5). A scenario name becomes
 * the simple class name with an ordinal prefix: T01_BallastTest and so on —
 * the alphabetical class order matches the recorded run order.
 */
sealed interface Scenario {
    /** The scenario name — part of the simple class name (T01_<name>Test). */
    val name: String

    /** Ballast: [testCount] always-green tests (spec: ~200 total, 30 of them "catch nothing"). */
    data class SimplePassing(override val name: String, val testCount: Int = 1) : Scenario

    /** Victim: asserts that the key [key] is clean in the static state. */
    data class Victim(override val name: String, val key: String) : Scenario

    /** Polluter: pollutes the key [key] that the victim asserts. */
    data class PolluterPollutes(override val name: String, val key: String) : Scenario

    /** Sets the system property [property] in a static initializer (visible before any test runs). */
    data class GuardSetup(override val name: String, val property: String) : Scenario

    /** @BeforeAll requires the [guardProperty] property: without it, an infra failure at the setup stage. */
    data class BeforeAllBroken(override val name: String, val guardProperty: String) : Scenario

    /** Unstable victim: the 2nd run in a JVM fails, the 1st and 3rd pass. */
    data class UnstableVictim(override val name: String) : Scenario

    /**
     * Victim with a boundary count: under pollution of the key [key] it fails on runs
     * 2–4 (ddmin repeat=3 → 2/3 failed; confirmation repeat=5 → 3/5 → UNCONFIRMED).
     */
    data class UnconfirmedVictim(override val name: String, val key: String) : Scenario

    /** Parameterized test — recorded under the display names "check(int)[N]" (for UNSUPPORTED). */
    data class Parameterized(override val name: String) : Scenario
}

/** A generated test class of the fixture project. */
data class GeneratedClass(val scenario: Scenario, val className: String, val methods: List<String>)

/** A generated fixture project: the [dir] root + the classes in the recorded order. */
data class Fixture(val dir: Path, val classes: List<GeneratedClass>) {
    fun reportsDir(): Path = dir.resolve("target").resolve("surefire-reports")
}

private const val FIXTURE_PACKAGE = "io.github.wrinfotel.fixture"

private val TEMPLATES = mapOf(
    "pom.xml" to "pom.xml.templ",
    "PollutionState" to "PollutionState.java.templ",
    "SimplePassing" to "SimplePassing.java.templ",
    "Victim" to "Victim.java.templ",
    "Polluter" to "Polluter.java.templ",
    "GuardSetup" to "GuardSetup.java.templ",
    "BeforeAllBroken" to "BeforeAllBroken.java.templ",
    "UnstableVictim" to "UnstableVictim.java.templ",
    "UnconfirmedVictim" to "UnconfirmedVictim.java.templ",
    "Parameterized" to "Parameterized.java.templ",
)

private fun template(key: String): String =
    GeneratedClass::class.java.getResourceAsStream("/fixture-templates/${TEMPLATES.getValue(key)}")!!
        .readBytes().toString(Charsets.UTF_8)

private fun render(templateText: String, vars: Map<String, String>): String =
    vars.entries.fold(templateText) { acc, (name, value) -> acc.replace("{{$name}}", value) }

private fun methodNames(scenario: Scenario): List<String> = when (scenario) {
    is Scenario.SimplePassing -> (1..scenario.testCount).map { "t$it" }
    is Scenario.Victim -> listOf("shouldWork")
    is Scenario.PolluterPollutes -> listOf("pollutes")
    is Scenario.GuardSetup -> listOf("setup")
    is Scenario.BeforeAllBroken -> listOf("shouldPassIfReached")
    is Scenario.UnstableVictim -> listOf("flaky")
    is Scenario.UnconfirmedVictim -> listOf("shouldWork")
    is Scenario.Parameterized -> listOf("check")
}

private fun renderBody(scenario: Scenario, className: String): String = when (scenario) {
    is Scenario.SimplePassing -> {
        val methods = methodNames(scenario).joinToString("\n") { method ->
            "    @Test\n    void $method() {\n    }"
        }
        render(template("SimplePassing"), mapOf("NAME" to className, "METHODS" to methods))
    }
    is Scenario.Victim -> render(template("Victim"), mapOf("NAME" to className, "KEY" to scenario.key))
    is Scenario.PolluterPollutes ->
        render(template("Polluter"), mapOf("NAME" to className, "KEY" to scenario.key))
    is Scenario.GuardSetup ->
        render(template("GuardSetup"), mapOf("NAME" to className, "KEY" to scenario.property))
    is Scenario.BeforeAllBroken ->
        render(template("BeforeAllBroken"), mapOf("NAME" to className, "KEY" to scenario.guardProperty))
    is Scenario.UnstableVictim -> render(template("UnstableVictim"), mapOf("NAME" to className))
    is Scenario.UnconfirmedVictim ->
        render(template("UnconfirmedVictim"), mapOf("NAME" to className, "KEY" to scenario.key))
    is Scenario.Parameterized -> render(template("Parameterized"), mapOf("NAME" to className))
}

/**
 * Generates a mini Maven project (plan Task 4.1): a pom + PollutionState + one class
 * per scenario, in plain Java. The classes are named T<NN>_<Name>Test — the recorded
 * run order matches the order of [scenarios]; equality with the alphabetical order
 * is ensured by surefire `runOrder=alphabetical` in the pom template (the filesystem
 * default is platform-dependent: on Linux the order is arbitrary — failed on CI, 2026-09-29).
 */
fun generateFixture(dir: Path, scenarios: List<Scenario>): Fixture {
    val packageDir = FIXTURE_PACKAGE.split('.').fold(sourcesRoot(dir)) { acc, part -> acc.resolve(part) }
    Files.createDirectories(packageDir)
    Files.writeString(dir.resolve("pom.xml"), template("pom.xml"))
    Files.writeString(packageDir.resolve("PollutionState.java"), template("PollutionState"))

    val classes = scenarios.mapIndexed { index, scenario ->
        val simpleName = "T%02d_%sTest".format(index + 1, scenario.name)
        val body = renderBody(scenario, simpleName)
        Files.writeString(packageDir.resolve("$simpleName.java"), body)
        GeneratedClass(scenario, "$FIXTURE_PACKAGE.$simpleName", methodNames(scenario))
    }
    return Fixture(dir, classes)
}

private fun sourcesRoot(dir: Path): Path =
    dir.resolve("src").resolve("test").resolve("java")
