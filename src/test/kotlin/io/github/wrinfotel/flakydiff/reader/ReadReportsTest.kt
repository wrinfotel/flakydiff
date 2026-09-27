package io.github.wrinfotel.flakydiff.reader

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ReadReportsTest {

    @TempDir
    lateinit var dir: Path

    private fun write(name: String, xml: String, modifiedAt: Instant? = null) {
        val p = dir.resolve(name)
        Files.writeString(p, xml)
        if (modifiedAt != null) Files.setLastModifiedTime(p, FileTime.from(modifiedAt))
    }

    private fun suiteXml(className: String, timestamp: String?): String {
        val tsAttr = timestamp?.let { " timestamp=\"$it\"" } ?: ""
        return """<testsuite name="$className" time="0.2" tests="1">
  <testcase name="m1" classname="$className" time="0.2"$tsAttr/>
</testsuite>"""
    }

    companion object {
        // (sequential, hasTimestamp, noForks, expected order_unreliable)
        // контракт Task 1.4 п.4: без --sequential флаг true в каждой ячейке;
        // true снимает флаг только при TIMESTAMP без срабатывания эвристики;
        // false — флаг всегда.
        @JvmStatic
        fun cases(): Stream<Arguments> = Stream.of(
            Arguments.of(null, true, false, true),
            Arguments.of(null, true, true, true),
            Arguments.of(null, false, false, true),
            Arguments.of(null, false, true, true),
            Arguments.of(true, true, false, false),
            Arguments.of(true, true, true, false),
            Arguments.of(true, false, false, true),
            Arguments.of(true, false, true, true),
            Arguments.of(false, true, false, true),
            Arguments.of(false, true, true, true),
            Arguments.of(false, false, false, true),
            Arguments.of(false, false, true, true),
        )

        @JvmStatic
        fun forkCases(): Stream<Arguments> = Stream.of(
            Arguments.of(false, true),
            Arguments.of(true, false),
        )
    }

    @ParameterizedTest(name = "sequential={0} timestamp={1} noForks={2} → order_unreliable={3}")
    @MethodSource("cases")
    fun `full readReports matrix matches spec contract`(
        sequential: Boolean?,
        hasTimestamp: Boolean,
        noForks: Boolean,
        expectedUnreliable: Boolean,
    ) {
        val base = Instant.parse("2026-09-27T10:00:00Z")
        if (hasTimestamp) {
            // timestamp ≥1с друг от друга → эвристика <1с не срабатывает
            write("a-beta.xml", suiteXml("com.acme.BetaTest", timestamp = "2026-09-27T10:00:01Z"))
            write("z-alpha.xml", suiteXml("com.acme.AlphaTest", timestamp = "2026-09-27T10:00:05Z"))
        } else {
            // start = mtime - time: у Beta mtime раньше на 60с → без timestamp
            write("a-beta.xml", suiteXml("com.acme.BetaTest", timestamp = null), modifiedAt = base.plusSeconds(60))
            write("z-alpha.xml", suiteXml("com.acme.AlphaTest", timestamp = null), modifiedAt = base.plusSeconds(120))
        }

        val result = readReports(dir, ReadOptions(sequential = sequential, noForks = noForks))

        assertEquals(expectedUnreliable, result.orderUnreliable)
        assertEquals(!noForks, result.forksPossible, "forks_possible: дефолт true, --no-forks снимает")
        assertEquals(
            if (hasTimestamp) OrderSource.TIMESTAMP else OrderSource.MTIME_HEURISTIC,
            result.orderSource,
        )
        assertEquals(2, result.run.entries.size)
    }

    @ParameterizedTest(name = "no-forks={0} → forks_possible={1}")
    @MethodSource("forkCases")
    fun `forks_possible defaults to true and only no-forks clears it`(noForks: Boolean, expectedForks: Boolean) {
        write("a.xml", suiteXml("com.acme.SoloTest", timestamp = "2026-09-27T10:00:01Z"))

        val result = readReports(dir, ReadOptions(noForks = noForks))

        assertEquals(expectedForks, result.forksPossible)
    }
}
