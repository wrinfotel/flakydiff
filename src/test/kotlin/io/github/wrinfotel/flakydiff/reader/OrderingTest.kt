package io.github.wrinfotel.flakydiff.reader

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderingTest {

    @TempDir
    lateinit var dir: Path

    private fun write(name: String, xml: String, modifiedAt: Instant? = null): Path {
        val p = dir.resolve(name)
        Files.writeString(p, xml)
        if (modifiedAt != null) Files.setLastModifiedTime(p, FileTime.from(modifiedAt))
        return p
    }

    private fun suiteXml(className: String, methods: List<String>, timestamp: String? = null): String {
        val cases = methods.joinToString("\n") { m ->
            val tsAttr = timestamp?.let { " timestamp=\"$it\"" } ?: ""
            """  <testcase name="$m" classname="$className" time="0.2"$tsAttr/>"""
        }
        return """<testsuite name="$className" time="0.4" tests="${methods.size}">
$cases
</testsuite>"""
    }

    @Test
    fun `exact timestamps order classes, sequential null keeps unreliable, true clears it`() {
        // alphabetical name order (Alpha < Beta) contradicts the timestamp order:
        // Beta started earlier (10:00:01 < 10:00:05), the order is taken from the timestamps
        write("a-beta.xml", suiteXml("com.acme.BetaTest", listOf("m1", "m2"), timestamp = "2026-09-27T10:00:01Z"))
        write("z-alpha.xml", suiteXml("com.acme.AlphaTest", listOf("m1", "m2"), timestamp = "2026-09-27T10:00:05Z"))

        val byDefault = readDirectory(dir, sequential = null)
        assertEquals(OrderSource.TIMESTAMP, byDefault.orderSource)
        assertEquals(
            listOf("com.acme.BetaTest", "com.acme.BetaTest", "com.acme.AlphaTest", "com.acme.AlphaTest"),
            byDefault.run.entries.map { it.ref.testClass },
        )
        assertEquals(listOf("m1", "m2"), byDefault.run.entries.take(2).map { it.ref.method })
        assertTrue(byDefault.orderUnreliable, "дефолт спеки: без --sequential порядок ненадёжен")

        val sequential = readDirectory(dir, sequential = true)
        assertTrue(!sequential.orderUnreliable, "timestamp ≥1с + --sequential → флаг снят")
    }

    @Test
    fun `timestamps closer than 1s keep order but stay unreliable even with sequential`() {
        write("a-beta.xml", suiteXml("com.acme.BetaTest", listOf("m1"), timestamp = "2026-09-27T10:00:01.000Z"))
        write("z-alpha.xml", suiteXml("com.acme.AlphaTest", listOf("m1"), timestamp = "2026-09-27T10:00:01.500Z"))

        val result = readDirectory(dir, sequential = true)

        assertEquals(OrderSource.TIMESTAMP, result.orderSource)
        assertEquals(listOf("com.acme.BetaTest", "com.acme.AlphaTest"), result.run.entries.map { it.ref.testClass })
        assertTrue(result.orderUnreliable, "соседние start < 1с → эвристика недостоверности")
    }

    @Test
    fun `no timestamps, mtimes 1s apart, order by mtime heuristic, unreliable even with sequential`() {
        val base = Instant.parse("2026-09-27T10:00:00Z")
        // start = mtime - time: Beta's mtime is 60s earlier → Beta started earlier
        write("a-beta.xml", suiteXml("com.acme.BetaTest", listOf("m1")), modifiedAt = base.plusSeconds(60))
        write("z-alpha.xml", suiteXml("com.acme.AlphaTest", listOf("m1")), modifiedAt = base.plusSeconds(120))

        val result = readDirectory(dir, sequential = true)

        assertEquals(OrderSource.MTIME_HEURISTIC, result.orderSource)
        assertEquals(listOf("com.acme.BetaTest", "com.acme.AlphaTest"), result.run.entries.map { it.ref.testClass })
        assertTrue(result.orderUnreliable, "нет timestamp → флаг остаётся даже при --sequential")

        val byDefault = readDirectory(dir, sequential = null)
        assertTrue(byDefault.orderUnreliable)
    }

    @Test
    fun `equal mtimes fall back to name order and stay unreliable`() {
        val base = Instant.parse("2026-09-27T10:00:00Z")
        write("a-beta.xml", suiteXml("com.acme.BetaTest", listOf("m1")), modifiedAt = base)
        write("z-alpha.xml", suiteXml("com.acme.AlphaTest", listOf("m1")), modifiedAt = base)

        val result = readDirectory(dir, sequential = true)

        assertEquals(OrderSource.MTIME_HEURISTIC, result.orderSource)
        assertEquals(listOf("com.acme.AlphaTest", "com.acme.BetaTest"), result.run.entries.map { it.ref.testClass })
        assertTrue(result.orderUnreliable)
    }

    @Test
    fun `broken xml file lands in errors, readable files still parsed`() {
        val base = Instant.parse("2026-09-27T10:00:00Z")
        write("ok.xml", suiteXml("com.acme.OkTest", listOf("m1"), timestamp = "2026-09-27T10:00:01Z"))
        write("broken.xml", "<testsuite name=\"com.acme.BrokenTest\"")

        val result = readDirectory(dir, sequential = true)

        assertEquals(1, result.errors.size)
        assertEquals(1, result.run.entries.size)
        assertEquals("com.acme.OkTest", result.run.entries[0].ref.testClass)
    }
}
