package com.scorchsoft.ssmarkdown.markdown

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/**
 * Coarse host-JVM timings for the tokenizer on a 20 MB Markdown corpus:
 * a full tokenise (the cost of opening a large file and letting the idle
 * pass finish), typing (lines re-tokenised and time per keystroke), and
 * opening a fence at the top (the viewport pass vs the whole idle pass).
 *
 * Opt-in, like `:core:text`'s heavy property tests: run with
 * `gradle -p apps/android :core:markdown:test -Pssmarkdown.heavyTests=true --tests '*TokenizerBenchmark*' -i`
 * and read the printed table. Phone numbers will be several times slower;
 * the point is how cost scales, not the absolute figure.
 */
class TokenizerBenchmarkTest {
    @Test
    fun `tokenize a 20 MB corpus`() {
        assumeTrue(System.getProperty("ssmarkdown.heavyTests") == "true")
        val doc = TestDoc(corpus(TARGET_CHARS))
        println("corpus: ${doc.lineCount} lines, ${doc.text.length / 1_048_576} MB")
        repeat(2) { IncrementalTokenizer(doc).continueInIdle(Long.MAX_VALUE / 4) } // JIT warm-up

        var cache = IncrementalTokenizer(doc)
        val fullNs = timed { cache = fullyTokenized(doc) }
        println(
            "full tokenize: ${fullNs / MS} ms (${doc.lineCount * 1_000L / (fullNs / MS)} lines/s), " +
                "${cache.outline.size} headings",
        )

        retained(doc)
        jumpFrames(doc)
        typing(doc, cache, paragraphsOnly = true)
        typing(doc, cache, paragraphsOnly = false)
        breaksNearTop(doc, cache)
        fenceAtTop(doc, cache, "```", "a ``` fence (closed by the corpus's next fence)")
        fenceAtTop(doc, cache, "~~~~", "a ~~~~ fence nothing closes (worst case)")
    }

    /** Heap kept alive by one fully tokenised cache (PERF-9): measured, then the cache's own estimate. */
    private fun retained(doc: TestDoc) {
        val before = usedHeap()
        val cache = fullyTokenized(doc)
        val after = usedHeap()
        println(
            "retained by one full tokenise: ${(after - before) / 1_048_576} MB measured, " +
                "${cache.retainedBytesEstimate() / 1_048_576} MB estimated, ${cache.cachedLines} lines of tokens held",
        )
    }

    /** Ten frames of 45 visible lines at line 585k before the accurate pass gets there (PERF-15). */
    private fun jumpFrames(doc: TestDoc) {
        val cache = IncrementalTokenizer(doc)
        cache.tokenizeUntil(VIEWPORT, BUDGET_NS)
        val first = minOf(JUMP_LINE, doc.lineCount - JUMP_VISIBLE)
        val frameNs = LongArray(JUMP_FRAMES)
        for (frame in 0 until JUMP_FRAMES) {
            frameNs[frame] = timed { for (line in first until first + JUMP_VISIBLE) cache.resultAt(line) }
        }
        println(
            "after a jump: first frame ${frameNs[0] / 1_000} µs, later frames ${frameNs
                .drop(
                    1,
                ).average()
                .toLong() / 1_000} µs, " +
                "${cache.linesTokenizedForDisplay} lines tokenised in $JUMP_FRAMES frames",
        )
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Inserts one char per edit and re-tokenises to the viewport below it, as the editor does per keystroke. */
    private fun typing(
        doc: TestDoc,
        cache: IncrementalTokenizer,
        paragraphsOnly: Boolean,
    ) {
        val random = Random(1)
        var touched = 0L
        var edits = 0
        val ns =
            timed {
                while (edits < EDITS) {
                    val line = random.nextInt(doc.lineCount)
                    if (paragraphsOnly && cache.resultAt(line).kind != LineKind.PARAGRAPH) continue
                    val text = doc.lines[line]
                    val col = random.nextInt(text.length + 1)
                    doc.lines[line] = text.substring(0, col) + "x" + text.substring(col)
                    cache.invalidate(line, 1, 1)
                    val before = cache.linesTokenized
                    cache.tokenizeUntil(minOf(line + VIEWPORT, doc.lineCount - 1), BUDGET_NS)
                    cache.continueInIdle(Long.MAX_VALUE / 4)
                    touched += cache.linesTokenized - before
                    edits++
                }
            }
        val where = if (paragraphsOnly) "in paragraphs" else "anywhere (fences, tables, markers too)"
        println(
            "typing $where: ${ns / EDITS / 1_000} µs/edit, ${touched.toDouble() / EDITS} lines re-tokenised per edit",
        )
    }

    /** Enter at line 10, then [BREAKS] more just below it (PERF-E): the state slots each break moves. */
    private fun breaksNearTop(
        doc: TestDoc,
        cache: IncrementalTokenizer,
    ) {
        val moved = cache.stateEntriesMoved
        doc.lines.add(10, "")
        val firstNs = timed { cache.invalidate(10, 1, 2) }
        val afterFirst = cache.stateEntriesMoved
        var restNs = 0L
        for (i in 1..BREAKS) {
            doc.lines.add(10 + i, "") // The test document's own O(n) shift: not timed.
            restNs += timed { cache.invalidate(10 + i, 1, 2) }
        }
        println(
            "line breaks near the top: first ${firstNs / 1_000} µs (${afterFirst - moved} slots moved), " +
                "then ${restNs / BREAKS / 1_000.0} µs each (${cache.stateEntriesMoved - afterFirst} slots for $BREAKS)",
        )
        repeat(BREAKS + 1) { doc.lines.removeAt(10) }
        cache.invalidate(10, BREAKS + 2, 1)
        cache.continueInIdle(Long.MAX_VALUE / 4)
    }

    private fun fenceAtTop(
        doc: TestDoc,
        cache: IncrementalTokenizer,
        fence: String,
        label: String,
    ) {
        doc.lines.add(0, fence)
        cache.invalidate(0, 0, 1)
        val before = cache.linesTokenized
        val viewportNs = timed { cache.tokenizeUntil(VIEWPORT, BUDGET_NS) }
        val viewportLines = cache.linesTokenized - before
        var slices = 0
        val idleNs = timed { while (!cache.continueInIdle(BUDGET_NS)) slices++ }
        println(
            "$label at line 0: viewport ${viewportNs / 1_000} µs ($viewportLines lines); " +
                "idle pass ${idleNs / MS} ms in ${slices + 1} slices of 8 ms (${cache.linesTokenized - before} lines)",
        )
        doc.lines.removeAt(0)
        cache.invalidate(0, 1, 0)
        cache.continueInIdle(Long.MAX_VALUE / 4)
    }

    private fun corpus(chars: Int): String {
        val random = Random(42)
        val out = StringBuilder(chars + 1_000)
        val blocks =
            listOf(
                "## Section heading with `code`\n\n",
                "Plain paragraph with *emphasis*, **strong**, a [link](https://example.com/p \"t\") and `code`.\n" +
                    "A second line of the paragraph, continuing with ~~struck~~ words and a footnote[^1].\n\n",
                "- item one\n- [ ] task two\n  - nested *three*\n\n",
                "```kotlin\nfun main() {\n    println(\"# not a heading\")\n}\n```\n\n",
                "> A quote with **bold**\n> spanning two lines\n\n",
                "| a | b |\n|---|:-:|\n| 1 | 2 |\n\n",
                "Setext heading\n--------------\n\n",
                "\$\$\nE = mc^2\n\$\$\n\n",
                "<div class=\"note\">\n<b>html</b>\n</div>\n\n",
                "[ref]: https://example.com \"Title\"\n\n",
            )
        while (out.length < chars) out.append(blocks[random.nextInt(blocks.size)])
        return out.toString()
    }

    private inline fun timed(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return System.nanoTime() - started
    }

    private companion object {
        const val TARGET_CHARS = 20 * 1_048_576
        const val EDITS = 2_000
        const val VIEWPORT = 60
        const val BUDGET_NS = 8_000_000L
        const val MS = 1_000_000L
        const val JUMP_LINE = 585_000
        const val JUMP_VISIBLE = 45
        const val JUMP_FRAMES = 10
        const val BREAKS = 1_000
    }
}
