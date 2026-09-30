package io.github.wrinfotel.flakydiff.fixture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Quick generator test: layout and placeholder substitution, without Maven. */
class FixtureProjectLayoutTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `renders pom and java sources with placeholders substituted`() {
        val fixture = generateFixture(
            tmp,
            listOf(
                Scenario.SimplePassing("Ballast", 2),
                Scenario.Victim("Victim", "v1"),
                Scenario.PolluterPollutes("Polluter", "v1"),
            ),
        )

        assertEquals(tmp, fixture.dir)

        val pom = Files.readString(tmp.resolve("pom.xml"))
        assertTrue(pom.contains("junit-jupiter"), pom)
        assertTrue(pom.contains("maven-surefire-plugin"), pom)

        val base = tmp.resolve("src/test/java/io/github/wrinfotel/fixture")
        val pollution = Files.readString(base.resolve("PollutionState.java"))
        assertTrue(pollution.contains("class PollutionState"), pollution)
        assertTrue(pollution.contains("isPolluted"), pollution)

        val ballast = Files.readString(base.resolve("T01_BallastTest.java"))
        assertTrue(ballast.contains("public class T01_BallastTest"), ballast)
        assertTrue(ballast.contains("void t1()"), ballast)
        assertTrue(ballast.contains("void t2()"), ballast)

        val victim = Files.readString(base.resolve("T02_VictimTest.java"))
        assertTrue(victim.contains("isPolluted(\"v1\")"), victim)

        val polluter = Files.readString(base.resolve("T03_PolluterTest.java"))
        assertTrue(polluter.contains("pollute(\"v1\")"), polluter)

        assertEquals(
            listOf(
                "io.github.wrinfotel.fixture.T01_BallastTest",
                "io.github.wrinfotel.fixture.T02_VictimTest",
                "io.github.wrinfotel.fixture.T03_PolluterTest",
            ),
            fixture.classes.map { it.className },
            "порядковый префикс T<NN>_ фиксирует записанный порядок: он совпадает с алфавитным",
        )
        assertEquals(listOf("t1", "t2"), fixture.classes[0].methods)
        assertEquals(listOf("shouldWork"), fixture.classes[1].methods)
        assertEquals(listOf("pollutes"), fixture.classes[2].methods)
    }

    @Test
    fun `each scenario kind renders its mechanic`() {
        val fixture = generateFixture(
            tmp,
            listOf(
                Scenario.GuardSetup("EnvSetup", "fd.guard.x"),
                Scenario.BeforeAllBroken("Broken", "fd.guard.x"),
                Scenario.UnstableVictim("Unstable"),
                Scenario.UnconfirmedVictim("Boundary", "v10"),
                Scenario.Parameterized("Param"),
            ),
        )

        val base = tmp.resolve("src/test/java/io/github/wrinfotel/fixture")

        val setup = Files.readString(base.resolve("T01_EnvSetupTest.java"))
        assertTrue(setup.contains("System.setProperty(\"fd.guard.x\""), setup)

        val broken = Files.readString(base.resolve("T02_BrokenTest.java"))
        assertTrue(broken.contains("@BeforeAll"), broken)
        assertTrue(broken.contains("getProperty(\"fd.guard.x\")"), broken)

        val unstable = Files.readString(base.resolve("T03_UnstableTest.java"))
        assertTrue(unstable.contains("AtomicInteger"), unstable)

        val boundary = Files.readString(base.resolve("T04_BoundaryTest.java"))
        assertTrue(boundary.contains("isPolluted(\"v10\")"), boundary)
        assertTrue(boundary.contains("AtomicInteger"), boundary)

        val param = Files.readString(base.resolve("T05_ParamTest.java"))
        assertTrue(param.contains("@ParameterizedTest"), param)

        assertEquals(listOf("shouldPassIfReached"), fixture.classes[1].methods)
        assertEquals(listOf("flaky"), fixture.classes[2].methods)
        assertEquals(listOf("shouldWork"), fixture.classes[3].methods)
        assertEquals(listOf("check"), fixture.classes[4].methods)
    }
}
