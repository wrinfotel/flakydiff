package io.github.wrinfotel.flakydiff.replay

import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

data class MvnResult(val exitCode: Int, val output: String = "")

/** Точка подмены mvn в тестах (план Task 2.4: фейковая обёртка со счётчиком вызовов). */
fun interface MvnRunner {
    fun run(projectDir: Path, args: List<String>): MvnResult
}

data class MavenProjectInfo(
    val projectDir: Path,
    val testClassesDir: Path,
    val classesDir: Path,
    /** Только зависимости из build-classpath; target-каталоги рядом отдельными полями. */
    val classpathEntries: List<Path>,
    val systemProperties: Map<String, String>,
    /** argLine после второго прохода интерполяции: сырые @{...}/${...} вырезаны с warning. */
    val argLine: String?,
    val systemPropertiesFile: String?,
    /** Профили с activeByDefault=true из effective-pom. */
    val profiles: List<String>,
    val warnings: List<String>,
)

private data class SurefireConfig(
    val systemPropertyVariables: Map<String, String>,
    val argLine: String?,
    val systemPropertiesFile: String?,
    val activatedProfiles: List<String>,
)

/**
 * Готовит чужой Maven-проект к зондам (спека §4.2):
 * 1. `-q test-compile dependency:build-classpath -Dmdep.outputFile=...` — оба шага
 *    обязательны, stdout не парсится, файл — источник истины;
 * 2. target/test-classes + target/classes добавляются руками (build-classpath их
 *    не включает);
 * 3. `help:effective-pom` → конфигурация surefire: systemPropertyVariables,
 *    argLine, systemPropertiesFile, активированные (activeByDefault) профили;
 * 4. argLine — второй проход интерполяции: сырые @{...}/${...} вырезаются с warning;
 * 5. classpath-файл и effective.xml кэшируются по SHA-256 всех pom реактора;
 *    test-compile выполняется ВСЕГДА (classpath от тестов не зависит, но их
 *    компиляция кэшироваться не должна — иначе e2e ломается на свежих тестах).
 */
fun prepareProject(
    projectDir: Path,
    workCacheDir: Path,
    mvn: MvnRunner = MvnRunner(::runMvn),
): MavenProjectInfo {
    Files.createDirectories(workCacheDir)
    val cacheKey = reactorPomsHash(projectDir)
    val cpFile = workCacheDir.resolve("cp-$cacheKey.txt")
    val effectiveFile = workCacheDir.resolve("effective-$cacheKey.xml")

    val buildArgs = mutableListOf("-q", "test-compile")
    if (!Files.exists(cpFile)) {
        buildArgs.add("dependency:build-classpath")
        buildArgs.add("-Dmdep.outputFile=$cpFile")
    }
    val build = mvn.run(projectDir, buildArgs)
    if (build.exitCode != 0) {
        throw IllegalStateException(
            "mvn test-compile failed in $projectDir (exit ${build.exitCode}):\n${build.output.takeLast(2000)}",
        )
    }
    if (!Files.exists(cpFile)) {
        throw IllegalStateException("mvn did not produce classpath file $cpFile")
    }

    if (!Files.exists(effectiveFile)) {
        val eff = mvn.run(projectDir, listOf("help:effective-pom", "-Doutput=$effectiveFile"))
        if (eff.exitCode != 0) {
            throw IllegalStateException(
                "mvn help:effective-pom failed in $projectDir (exit ${eff.exitCode}):\n${eff.output.takeLast(2000)}",
            )
        }
    }

    val cpText = Files.readString(cpFile).trim()
    val classpathEntries = if (cpText.isEmpty()) {
        emptyList()
    } else {
        cpText.split(File.pathSeparator).map { Path.of(it) }
    }

    val cfg = parseSurefireConfig(effectiveFile)
    val (argLine, argWarnings) = stripUnresolvedPlaceholders(cfg.argLine)
    return MavenProjectInfo(
        projectDir = projectDir,
        testClassesDir = projectDir.resolve("target").resolve("test-classes"),
        classesDir = projectDir.resolve("target").resolve("classes"),
        classpathEntries = classpathEntries,
        systemProperties = cfg.systemPropertyVariables,
        argLine = argLine,
        systemPropertiesFile = cfg.systemPropertiesFile,
        profiles = cfg.activatedProfiles,
        warnings = argWarnings,
    )
}

private val isWindows: Boolean =
    System.getProperty("os.name").lowercase().contains("win")

/** Windows: ProcessBuilder не запускает mvn.cmd напрямую — нужен cmd /c. */
internal val defaultMvnCommand: List<String> =
    if (isWindows) listOf("cmd", "/c", "mvn") else listOf("mvn")

private fun runMvn(projectDir: Path, args: List<String>): MvnResult {
    val proc = ProcessBuilder(defaultMvnCommand + args)
        .directory(projectDir.toFile())
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
    return MvnResult(proc.waitFor(), output)
}

private fun stripUnresolvedPlaceholders(argLine: String?): Pair<String?, List<String>> {
    if (argLine == null) return null to emptyList()
    val warnings = mutableListOf<String>()
    val cleaned = Regex("[@$]\\{[^}]+\\}").replace(argLine) { match ->
        warnings.add("removed unresolved placeholder '${match.value}' from argLine")
        ""
    }.trim()
    return cleaned.ifEmpty { null } to warnings
}

/** SHA-256 по всем pom реактора (root + modules рекурсивно). */
private fun reactorPomsHash(projectDir: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    reactorPoms(projectDir).forEach { pom ->
        md.update(projectDir.relativize(pom).toString().toByteArray(Charsets.UTF_8))
        md.update(Files.readAllBytes(pom))
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun reactorPoms(projectDir: Path): List<Path> {
    val result = mutableListOf<Path>()
    val visited = HashSet<String>()
    fun walk(dir: Path) {
        val pom = dir.resolve("pom.xml")
        if (!Files.exists(pom)) return
        if (!visited.add(pom.toAbsolutePath().normalize().toString())) return
        result.add(pom)
        val modules = parseXml(pom).getElementsByTagName("module")
        for (i in 0 until modules.length) {
            val module = modules.item(i).textContent.trim()
            if (module.isNotEmpty()) walk(dir.resolve(module))
        }
    }
    walk(projectDir)
    return result
}

private fun parseSurefireConfig(effectivePomFile: Path): SurefireConfig {
    val doc = parseXml(effectivePomFile)
    val plugins = doc.getElementsByTagName("plugin")
    var surefire: Element? = null
    for (i in 0 until plugins.length) {
        val plugin = plugins.item(i) as Element
        val groupId = plugin.directChild("groupId")?.textContent?.trim()
        val artifactId = plugin.directChild("artifactId")?.textContent?.trim()
        if (groupId == "org.apache.maven.plugins" && artifactId == "maven-surefire-plugin") {
            surefire = plugin
            break
        }
    }
    val configuration = surefire?.directChild("configuration")

    val systemProperties = LinkedHashMap<String, String>()
    configuration?.directChild("systemPropertyVariables")?.let { vars ->
        for (i in 0 until vars.childNodes.length) {
            val node = vars.childNodes.item(i)
            if (node is Element) {
                systemProperties[node.tagName] = node.textContent.trim()
            }
        }
    }

    val activatedProfiles = mutableListOf<String>()
    doc.getElementsByTagName("profiles").let { profilesLists ->
        for (i in 0 until profilesLists.length) {
            val profilesList = profilesLists.item(i) as Element
            for (j in 0 until profilesList.childNodes.length) {
                val node = profilesList.childNodes.item(j)
                if (node !is Element || node.tagName != "profile") continue
                val activeByDefault = node.directChild("activation")
                    ?.directChild("activeByDefault")?.textContent?.trim()
                if (activeByDefault == "true") {
                    activatedProfiles.add(node.directChild("id")?.textContent?.trim() ?: "")
                }
            }
        }
    }

    return SurefireConfig(
        systemPropertyVariables = systemProperties,
        argLine = configuration?.directChild("argLine")?.textContent,
        systemPropertiesFile = configuration?.directChild("systemPropertiesFile")?.textContent?.trim(),
        activatedProfiles = activatedProfiles.filter { it.isNotEmpty() },
    )
}

private fun Element.directChild(tag: String): Element? {
    for (i in 0 until childNodes.length) {
        val node = childNodes.item(i)
        if (node is Element && node.tagName == tag) return node
    }
    return null
}

private fun parseXml(file: Path): org.w3c.dom.Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    return factory.newDocumentBuilder().parse(file.toFile())
}
