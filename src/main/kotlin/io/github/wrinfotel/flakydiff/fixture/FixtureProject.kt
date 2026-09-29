package io.github.wrinfotel.flakydiff.fixture

import java.nio.file.Files
import java.nio.file.Path

/**
 * Сценарии fixture-проекта (план Task 4.1, спека §5). Имя сценария становится
 * простым именем класса с порядковым префиксом: T01_BallastTest и т.д. —
 * алфавитный порядок классов совпадает с записанным порядком прогона.
 */
sealed interface Scenario {
    /** Имя сценария — часть простого имени класса (T01_<name>Test). */
    val name: String

    /** Балласт: [testCount] всегда зелёных тестов (спека: ~200 суммарно, 30 «не ловят»). */
    data class SimplePassing(override val name: String, val testCount: Int = 1) : Scenario

    /** Жертва: ассертит, что ключ [key] в статике чист. */
    data class Victim(override val name: String, val key: String) : Scenario

    /** Polluter: грязнит ключ [key], который ассертит жертва. */
    data class PolluterPollutes(override val name: String, val key: String) : Scenario

    /** Ставит системное свойство [property] в static-инициализаторе (видно до любого теста). */
    data class GuardSetup(override val name: String, val property: String) : Scenario

    /** @BeforeAll требует свойство [guardProperty]: без него — инфра-фейл стадии подготовки. */
    data class BeforeAllBroken(override val name: String, val guardProperty: String) : Scenario

    /** Нестабильная жертва: 2-й запуск в JVM падает, 1-й и 3-й проходят. */
    data class UnstableVictim(override val name: String) : Scenario

    /**
     * Жертва с граничным счётом: под загрязнением ключа [key] падает на запусках
     * 2–4 (ddmin repeat=3 → 2/3 упавших; confirmation repeat=5 → 3/5 → UNCONFIRMED).
     */
    data class UnconfirmedVictim(override val name: String, val key: String) : Scenario

    /** Параметризованный тест — записывается display-именами «check(int)[N]» (для UNSUPPORTED). */
    data class Parameterized(override val name: String) : Scenario
}

/** Сгенерированный тестовый класс fixture-проекта. */
data class GeneratedClass(val scenario: Scenario, val className: String, val methods: List<String>)

/** Сгенерированный fixture-проект: корень [dir] + классы в записанном порядке. */
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
 * Генерирует мини-Maven проект (план Task 4.1): pom + PollutionState + по классу
 * на сценарий, в чистой Java. Классы называются T<NN>_<Name>Test — записанный
 * порядок прогона совпадает с порядком [scenarios]; равенство алфавитному порядку
 * обеспечивает surefire `runOrder=alphabetical` в pom-шаблоне (дефолт filesystem
 * платформо-зависим: на Linux порядок произвольный — упало на CI, 2026-09-29).
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
