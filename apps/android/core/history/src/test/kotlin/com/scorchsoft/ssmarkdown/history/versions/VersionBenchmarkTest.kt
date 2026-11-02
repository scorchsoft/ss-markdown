package com.scorchsoft.ssmarkdown.history.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * The storage budget from docs/robustness.md §3: 1,000 versions of a
 * 200 KB document with typical edits should take about 1–3 MB.
 *
 * Opt-in (`-Pssmarkdown.heavyTests=true`): it syncs 1,000 records to disk.
 * `check` runs the same measurement at a tenth of the size.
 */
class VersionBenchmarkTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a thousand versions of a 200 KB document`() {
        assumeTrue("heavy benchmark; pass -Pssmarkdown.heavyTests=true", Soak.heavy)
        val result = measure(versions = 1_000, documentBytes = 200_000)
        assertTrue("total ${result.totalBytes} bytes", result.totalBytes < 5_000_000)
    }

    @Test
    fun `a hundred versions of a 20 KB document`() {
        val result = measure(versions = 100, documentBytes = 20_000)
        assertTrue("total ${result.totalBytes} bytes", result.totalBytes < 200_000)
    }

    private class Measurement(
        val totalBytes: Long,
    )

    private fun measure(
        versions: Int,
        documentBytes: Int,
    ): Measurement {
        val random = Random(42)
        val doc = DocumentKey("file:///bench.md")
        val test = TestHistory(folder.newFolder())
        val sentence = "Lorem ipsum dolor sit amet, **consectetur** adipiscing elit, sed do eiusmod.\n"
        var text =
            buildString {
                while (length <
                    documentBytes
                ) {
                    append(if (random.nextInt(12) == 0) "## Heading\n" else sentence)
                }
            }
        val texts = ArrayList<Pair<VersionId, String>>()
        val recordStart = System.nanoTime()
        repeat(versions) { i ->
            text = typicalEdit(random, text, i)
            test.clock.advance(RetentionRules.HOUR)
            test.record(doc, text)?.let { texts += it.id to text }
        }
        val recordMillis = (System.nanoTime() - recordStart) / 1e6
        val total = test.store.usage().totalBytes
        val restore =
            listOf(0, texts.size / 2, texts.size - 1).associate { i ->
                val (id, expected) = texts[i]
                val start = System.nanoTime()
                val read = test.text(doc, id)
                val millis = (System.nanoTime() - start) / 1e6
                assertEquals(expected, read)
                i to millis
            }
        val keyframes = test.versions(doc).count { it.keyframe }
        println(
            "versions=${texts.size} docBytes=${text.length} totalBytes=$total keyframes=$keyframes " +
                "recordAvgMs=${"%.2f".format(recordMillis / versions)} restoreMs(oldest,middle,latest)=" +
                restore.values.joinToString { "%.1f".format(it) },
        )
        return Measurement(total)
    }

    /** One to three lines typed, rewritten or deleted somewhere in the document. */
    private fun typicalEdit(
        random: Random,
        text: String,
        i: Int,
    ): String {
        val lines = Lines.of(text).let { l -> MutableList(l.count) { l[it] } }
        repeat(random.nextInt(1, 4)) {
            val at = random.nextInt(lines.size)
            when (random.nextInt(4)) {
                0 -> lines.add(at, "New thought $i: ${random.nextLong()}\n")
                1 -> lines.removeAt(at)
                else -> lines[at] = lines[at].trimEnd('\n') + " (edited $i)\n"
            }
        }
        return lines.joinToString("")
    }
}
