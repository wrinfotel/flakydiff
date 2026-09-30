package io.github.wrinfotel.flakydiff.replay

import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

data class MvnResult(val exitCode: Int, val output: String = "")

/** mvn substitution point in tests (plan Task 2.4: a fake wrapper with a call counter). */
fun interface MvnRunner {
    fun run(projectDir: Path, args: List<String>): MvnResult
}

data class MavenProjectInfo(
    val projectDir: Path,
    val testClassesDir: Path,
    val classesDir: Path,
    /** Only dependencies from build-classpath; the target directories alongside are separate fields. */
    val classpathEntries: List<Path>,
    /** The probe's final properties: systemPropertiesFile + systemPropertyVariables (explicit ones win). */
    val systemProperties: Map<String, String>,
    /** argLine after the second interpolation pass: raw @{...}/${...} are cut out with a warning. */
    val argLine: String?,
    val systemPropertiesFile: String?,
    /** Profiles with activeByDefault=true from the effective pom. */
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
 * Prepares a foreign Maven project for probes (spec §4.2):
 * 1. `-q test-compile dependency:build-classpath -Dmdep.outputFile=...` — both steps
 *    are mandatory, stdout is not parsed, the file is the source of truth;
 * 2. target/test-classes + target/classes are added manually (build-classpath does
 *    not include them);
 * 3. `help:effective-pom` → the surefire configuration: systemPropertyVariables,
 *    argLine, systemPropertiesFile, activated (activeByDefault) profiles;
 * 4. argLine — a second interpolation pass: raw @{...}/${...} are cut out with a warning;
 * 5. the classpath file and effective.xml are cached by SHA-256 of all reactor poms;
 *    test-compile runs ALWAYS (the classpath does not depend on the tests, but their
 *    compilation must not be cached — otherwise e2e breaks on fresh tests).
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
    val warnings = argWarnings.toMutableList()
    val fileProps = loadFileProperties(projectDir, cfg.systemPropertiesFile, warnings)
    return MavenProjectInfo(
        projectDir = projectDir,
        testClassesDir = projectDir.resolve("target").resolve("test-classes"),
        classesDir = projectDir.resolve("target").resolve("classes"),
        classpathEntries = classpathEntries,
        // The probe's final properties (spec §4.2): file + explicit systemPropertyVariables,
        // explicit ones win — the same precedence resolution as surefire.
        systemProperties = fileProps + cfg.systemPropertyVariables,
        argLine = argLine,
        systemPropertiesFile = cfg.systemPropertiesFile,
        profiles = cfg.activatedProfiles,
        warnings = warnings,
    )
}

/**
 * systemPropertiesFile from the surefire configuration (spec §4.2): a path relative
 * to the project basedir (or absolute). A missing file is a warning, not a crash:
 * the rest of the configuration stays valid.
 */
private fun loadFileProperties(
    projectDir: Path,
    systemPropertiesFile: String?,
    warnings: MutableList<String>,
): Map<String, String> {
    if (systemPropertiesFile == null) return emptyMap()
    val raw = Path.of(systemPropertiesFile)
    val file = if (raw.isAbsolute) raw else projectDir.resolve(raw)
    if (!Files.exists(file)) {
        warnings.add("systemPropertiesFile not found: $file (surefire properties skipped)")
        return emptyMap()
    }
    val props = java.util.Properties()
    Files.newInputStream(file).use { props.load(it) }
    return props.entries.associate { (k, v) -> k.toString() to v.toString() }
}

private val isWindows: Boolean =
    System.getProperty("os.name").lowercase().contains("win")

/** Windows: ProcessBuilder does not launch mvn.cmd directly — cmd /c is needed. */
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

/** SHA-256 over all reactor poms (root + modules recursively). */
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
    // In the effective pom the <profiles> and <build>/<pluginManagement> sections come
    // BEFORE the real declaration in build/plugins (corporate parent poms, inactive
    // profiles). Taking the first match would pick up someone else's configuration:
    // select by context priority, and on a tie — the last one (a later reactor module overrides).
    var surefire: Element? = null
    var bestTier = -1
    for (i in 0 until plugins.length) {
        val plugin = plugins.item(i) as Element
        val groupId = plugin.directChild("groupId")?.textContent?.trim()
        val artifactId = plugin.directChild("artifactId")?.textContent?.trim()
        if (groupId == "org.apache.maven.plugins" && artifactId == "maven-surefire-plugin") {
            val tier = pluginTier(plugin)
            if (tier >= bestTier) {
                bestTier = tier
                surefire = plugin
            }
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

/**
 * Surefire plugin context priority: 2 — build/plugins (the real execution
 * configuration; the effective pom also merges activated profiles here), 1 — pluginManagement
 * (defaults, in effect only while there is no configuration of one's own), 0 — the profiles
 * section (an inactive profile must not shadow build).
 */
private fun pluginTier(plugin: Element): Int {
    var inManagement = false
    var inProfiles = false
    var node = plugin.parentNode
    while (node is Element) {
        when (node.tagName) {
            "pluginManagement" -> inManagement = true
            "profiles" -> inProfiles = true
        }
        node = node.parentNode
    }
    return when {
        !inManagement && !inProfiles -> 2
        inManagement -> 1
        else -> 0
    }
}

private fun parseXml(file: Path): org.w3c.dom.Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    return factory.newDocumentBuilder().parse(file.toFile())
}
