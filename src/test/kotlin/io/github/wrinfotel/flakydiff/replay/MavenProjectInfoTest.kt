package io.github.wrinfotel.flakydiff.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * An mvn stand-in — a recording fake with a per-subcommand call counter (plan,
 * Task 2.4 item 5): build-classpath writes the cp file, help:effective-pom — effective.xml.
 * The file is the source of truth; stdout is not parsed.
 */
class MavenProjectInfoTest {

    @TempDir
    lateinit var tmp: Path

    private class RecordingRunner(val effectivePomXml: String) : MvnRunner {
        val calls = mutableListOf<List<String>>()
        var failNextWith: String? = null

        override fun run(projectDir: Path, args: List<String>): MvnResult {
            calls.add(args)
            failNextWith?.let { return MvnResult(1, it) }
            if (args.contains("dependency:build-classpath")) {
                val outFile = Path.of(argValue(args, "mdep.outputFile")!!)
                val depA = outFile.resolveSibling("dep-one.jar")
                val depB = outFile.resolveSibling("dep-two.jar")
                Files.writeString(depA, "stub")
                Files.writeString(depB, "stub")
                Files.writeString(outFile, listOf(depA, depB).joinToString(File.pathSeparator))
            }
            if (args.contains("help:effective-pom")) {
                Files.writeString(Path.of(argValue(args, "output")!!), effectivePomXml)
            }
            return MvnResult(0, "")
        }

        private fun argValue(args: List<String>, key: String): String? =
            args.firstOrNull { it.startsWith("-D$key=") }?.substringAfter('=')
    }

    private fun recordingRunner(): RecordingRunner =
        RecordingRunner(javaClass.getResourceAsStream("/fixture-mvn/effective-pom.xml")!!
            .readBytes().toString(Charsets.UTF_8))

    private fun fixtureProject(): Path {
        val dir = tmp.resolve("project")
        Files.createDirectories(dir)
        javaClass.getResourceAsStream("/fixture-mvn/pom.xml")!!.use { input ->
            Files.copy(input, dir.resolve("pom.xml"))
        }
        return dir
    }

    private fun cacheDir(): Path = tmp.resolve("cache")

    @Test
    fun `prepares classpath, target dirs and surefire config`() {
        val runner = recordingRunner()
        val project = fixtureProject()

        val info = prepareProject(project, cacheDir(), runner)

        // call 1: -q test-compile + dependency:build-classpath (both steps are required);
        // call 2: help:effective-pom
        assertEquals(2, runner.calls.size, "calls:\n${runner.calls.joinToString("\n")}")
        assertTrue(runner.calls[0].contains("-q"))
        assertTrue(runner.calls[0].contains("test-compile"))
        assertTrue(runner.calls[0].contains("dependency:build-classpath"))
        val cpArg = runner.calls[0].first { it.startsWith("-Dmdep.outputFile=") }
        assertTrue(cpArg.endsWith(".txt") && Path.of(cpArg.substringAfter('=')).parent == cacheDir().toAbsolutePath().normalize(),
            "cp file must live in cache dir: $cpArg")
        assertTrue(runner.calls[1].contains("help:effective-pom"))

        assertEquals(project.resolve("target").resolve("test-classes"), info.testClassesDir)
        assertEquals(project.resolve("target").resolve("classes"), info.classesDir)
        // build-classpath returns only dependencies — the target directories are added manually
        assertEquals(
            listOf("dep-one.jar", "dep-two.jar").map { cacheDir().resolve(it).toAbsolutePath().normalize() },
            info.classpathEntries,
        )
        assertEquals(mapOf("fixtureProp" to "fixtureValue"), info.systemProperties)
        assertEquals("src/test/resources/test.properties", info.systemPropertiesFile)
        assertEquals(listOf("ci"), info.profiles)
    }

    @Test
    fun `argLine second-pass strips raw placeholders with warning`() {
        val runner = recordingRunner()

        val info = prepareProject(fixtureProject(), cacheDir(), runner)

        assertEquals("-Xmx512m -DfromFixture=1", info.argLine, "сырые @{...} вырезаются")
        assertTrue(info.warnings.any { it.contains("@{argLine}") },
            "warning обязателен: ${info.warnings}")
    }

    @Test
    fun `cache hit skips build-classpath and effective-pom but never test-compile`() {
        val runner = recordingRunner()
        val project = fixtureProject()

        val first = prepareProject(project, cacheDir(), runner)
        val second = prepareProject(project, cacheDir(), runner)

        // calls: [tc+bc], [eff], [tc] — the third one WITHOUT build-classpath and WITHOUT effective-pom
        assertEquals(3, runner.calls.size, "calls:\n${runner.calls.joinToString("\n")}")
        assertTrue(runner.calls[2].contains("test-compile"))
        assertTrue(!runner.calls[2].contains("dependency:build-classpath"))
        assertTrue(!runner.calls[2].contains("help:effective-pom"))
        assertEquals(first.classpathEntries, second.classpathEntries)
        assertEquals(first.argLine, second.argLine)
        assertEquals(first.systemProperties, second.systemProperties)
    }

    @Test
    fun `changed pom invalidates cache`() {
        val runner = recordingRunner()
        val project = fixtureProject()

        prepareProject(project, cacheDir(), runner)
        Files.writeString(project.resolve("pom.xml"),
            Files.readString(project.resolve("pom.xml")).replace("<version>1.0</version>", "<version>1.1</version>"))
        prepareProject(project, cacheDir(), runner)

        // [tc+bc], [eff], [tc+bc], [eff] — the pom hash changed, the cache missed
        assertEquals(4, runner.calls.size, "calls:\n${runner.calls.joinToString("\n")}")
        assertTrue(runner.calls[2].contains("dependency:build-classpath"))
        assertTrue(runner.calls[3].contains("help:effective-pom"))
    }

    @Test
    fun `mvn failure surfaces stderr, not swallowed`() {
        val runner = recordingRunner().apply { failNextWith = "boom-stderr-tail" }

        val ex = assertThrows(IllegalStateException::class.java) {
            prepareProject(fixtureProject(), cacheDir(), runner)
        }
        assertTrue(ex.message!!.contains("boom-stderr-tail"), "message: ${ex.message}")
    }

    /** Spec §4.2 (blocking): systemPropertiesFile must reach the probes. */
    private fun effectivePomWithVars(): String = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>fix</groupId><artifactId>f</artifactId><version>1</version>
          <build><plugins><plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
              <systemPropertyVariables>
                <fixtureProp>fixtureValue</fixtureProp>
                <overridden>explicitValue</overridden>
              </systemPropertyVariables>
              <systemPropertiesFile>src/test/resources/test.properties</systemPropertiesFile>
            </configuration>
          </plugin></plugins></build>
        </project>
    """.trimIndent()

    @Test
    fun `systemPropertiesFile properties reach probes, explicit pom variables win`() {
        val project = fixtureProject()
        val props = project.resolve("src/test/resources/test.properties")
        Files.createDirectories(props.parent)
        Files.writeString(props, "fromFile=fileValue\noverridden=fromFileValue\n")
        val runner = RecordingRunner(effectivePomWithVars())

        val info = prepareProject(project, cacheDir(), runner)

        assertEquals(
            mapOf(
                "fromFile" to "fileValue",
                "fixtureProp" to "fixtureValue",
                "overridden" to "explicitValue",
            ),
            info.systemProperties,
            "файл обязан попасть в свойства зонда; явные systemPropertyVariables побеждают",
        )
    }

    @Test
    fun `missing systemPropertiesFile is a warning, not a crash`() {
        val info = prepareProject(fixtureProject(), cacheDir(), RecordingRunner(effectivePomWithVars()))

        assertTrue(
            info.warnings.any { it.contains("systemPropertiesFile") },
            "warnings: ${info.warnings}",
        )
        assertEquals(
            mapOf("fixtureProp" to "fixtureValue", "overridden" to "explicitValue"),
            info.systemProperties,
            "явные переменные остаются",
        )
    }

    private fun surefirePlugin(argLine: String): String = """
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <configuration><argLine>$argLine</argLine></configuration>
        </plugin>
    """.trimIndent()

    private fun pomXml(body: String): String = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>fix</groupId><artifactId>f</artifactId><version>1</version>
          $body
        </project>
    """.trimIndent()

    @Test
    fun `build plugins surefire wins over earlier profile declaration`() {
        // effective-pom keeps the profiles section BEFORE build: an inactive profile with
        // a surefire config must not shadow the real execution configuration
        val xml = pomXml(
            """
            <profiles>
              <profile>
                <id>legacy</id>
                <build><plugins>${surefirePlugin("PROFILE-ONLY")}</plugins></build>
              </profile>
            </profiles>
            <build><plugins>${surefirePlugin("REAL")}</plugins></build>
            """.trimIndent(),
        )

        val info = prepareProject(fixtureProject(), cacheDir(), RecordingRunner(xml))

        assertEquals("REAL", info.argLine, "конфиг build/plugins обязателен, не первый <plugin> в документе")
    }

    @Test
    fun `build plugins surefire wins over pluginManagement`() {
        // pluginManagement (corporate parent-poms) comes before build/plugins
        val xml = pomXml(
            """
            <build>
              <pluginManagement><plugins>${surefirePlugin("MGMT")}</plugins></pluginManagement>
              <plugins>${surefirePlugin("REAL")}</plugins>
            </build>
            """.trimIndent(),
        )

        val info = prepareProject(fixtureProject(), cacheDir(), RecordingRunner(xml))

        assertEquals("REAL", info.argLine, "объявление в build/plugins затеняет pluginManagement")
    }

    @Test
    fun `pluginManagement surefire used when build has no declaration`() {
        val xml = pomXml(
            """
            <build>
              <pluginManagement><plugins>${surefirePlugin("MGMT-ONLY")}</plugins></pluginManagement>
            </build>
            """.trimIndent(),
        )

        val info = prepareProject(fixtureProject(), cacheDir(), RecordingRunner(xml))

        assertEquals("MGMT-ONLY", info.argLine, "без build-объявления берутся дефолты pluginManagement")
    }
}
