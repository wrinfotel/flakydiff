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
 * Подмена mvn — записывающий фейк со счётчиком вызовов по подкомандам (план,
 * Task 2.4 п.5): build-classpath пишет cp-файл, help:effective-pom — effective.xml.
 * Файл — источник истины, stdout не парсится.
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

        // 1 вызов: -q test-compile + dependency:build-classpath (оба шага обязательны);
        // 2 вызов: help:effective-pom
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
        // build-classpath отдаёт только зависимости — target-каталоги добавляются руками
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

        // вызовы: [tc+bc], [eff], [tc] — третий БЕЗ build-classpath и БЕЗ effective-pom
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

        // [tc+bc], [eff], [tc+bc], [eff] — хэш poms сменился, кэш промахнулся
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
}
