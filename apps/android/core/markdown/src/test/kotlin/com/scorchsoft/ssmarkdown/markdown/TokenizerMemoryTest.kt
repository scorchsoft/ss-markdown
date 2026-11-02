package com.scorchsoft.ssmarkdown.markdown

import com.scorchsoft.ssmarkdown.text.PieceTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenizer's memory and per-frame work stay bounded on a large
 * document (performance review PERF-9, PERF-15, PERF-16). Counts, not
 * timings, so nothing here flakes on a busy machine.
 */
class TokenizerMemoryTest {
    private fun bigDoc(lines: Int): TestDoc =
        TestDoc(
            (0 until lines).joinToString("\n") { i ->
                when (i % 10) {
                    0 -> "## Section $i"
                    5 -> ""
                    else -> "Some *text* on line $i with `code` and a [link](u)."
                }
            },
        )

    @Test
    fun `a fully tokenised document keeps states for every line but tokens for only a few (PERF-9)`() {
        val doc = bigDoc(200_000)
        val cache = fullyTokenized(doc)
        // Before: one LineResult per line, 200,000 of them (about 100 bytes a line, 121 MB for 20 MB of text).
        assertTrue("${cache.cachedLines} lines of tokens held", cache.cachedLines <= 1_024)
        val perLine = cache.retainedBytesEstimate().toDouble() / doc.lineCount
        // Ten bytes of slots a line, plus one heading (~90 bytes with its text) every ten lines (PERF-J counts them).
        assertTrue("$perLine bytes a line", perLine < 24.0)
        assertEquals(20_000, cache.outline.size)
    }

    @Test
    fun `a run of line breaks near the top of a huge file moves no state after the first (PERF-E)`() {
        val doc = bigDoc(HUGE_LINES)
        val cache = fullyTokenized(doc)
        // The first break near the top pays once to bring the gap there.
        doc.edit(cache, doc.offsetOf(10), doc.offsetOf(10), "\n")
        val first = cache.stateEntriesMoved
        assertTrue("$first moved for the first break", first <= HUGE_LINES)
        var line = 11
        repeat(BREAKS) {
            val at = doc.offsetOf(line) + 2
            doc.edit(cache, at, at, "\n")
            cache.continueInIdle(Long.MAX_VALUE / 4)
            line++
        }
        // Before: every break shifted the whole tail of four arrays, 200k entries each, 40M moves for 200 breaks.
        val moved = cache.stateEntriesMoved - first
        assertTrue("$moved state slots moved for $BREAKS line breaks", moved <= BREAKS * 2L)
        assertTrue(cache.isComplete)
        val fresh = fullyTokenized(doc)
        for (probe in listOf(0, 10, 11, 12, 100, 211, 5_000, doc.lineCount - 1)) {
            assertTrue("line $probe", cache.resultAt(probe).sameAs(fresh.resultAt(probe)))
        }
    }

    @Test
    fun `typing inside lines never moves a state slot (PERF-E)`() {
        val doc = bigDoc(HUGE_LINES)
        val cache = fullyTokenized(doc)
        repeat(BREAKS) {
            val at = doc.offsetOf(500) + 3
            doc.edit(cache, at, at, "x")
        }
        assertEquals(0L, cache.stateEntriesMoved)
    }

    @Test
    fun `the memory estimate counts headings and references, not just the line slots (PERF-J)`() {
        val doc =
            TestDoc(
                (0 until 20_000).joinToString("\n") { i ->
                    if (i % 2 ==
                        0
                    ) {
                        "## A heading with a fairly long title number $i"
                    } else {
                        "[label$i]: https://example.com/$i"
                    }
                },
            )
        val cache = fullyTokenized(doc)
        val slotsOnly = doc.lineCount.toLong() * IncrementalTokenizer.BYTES_PER_LINE
        // 10k headings of ~45 chars and 10k labels: well over 1 MB beyond the slots, which the old estimate ignored.
        assertTrue(
            "${cache.retainedBytesEstimate()} bytes estimated",
            cache.retainedBytesEstimate() > slotsOnly + 1_000_000,
        )
    }

    @Test
    fun `an outline tokenizer keeps no tokens at all`() {
        val doc = bigDoc(10_000)
        val cache = IncrementalTokenizer(doc, tokenCacheLines = 0)
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(0, cache.cachedLines)
        assertEquals(1_000, cache.outline.size)
        assertEquals(LineKind.HEADING, cache.resultAt(9_990).kind)
    }

    @Test
    fun `trimming drops every cached token and changes no answer`() {
        val doc = bigDoc(2_000)
        val cache = fullyTokenized(doc)
        val before = (0 until 100).map { cache.resultAt(it) }
        cache.trimCache()
        assertEquals(0, cache.cachedLines)
        (0 until 100).forEach { assertTrue(before[it].sameAs(cache.resultAt(it))) }
    }

    @Test
    fun `a viewport far below the accurate pass is guessed once, not every frame (PERF-15)`() {
        val doc = bigDoc(600_000)
        val cache = IncrementalTokenizer(doc)
        cache.tokenizeUntil(60, Long.MAX_VALUE / 4)
        val viewport = 585_000 until 585_045
        val before = cache.linesTokenizedForDisplay
        repeat(FRAMES) { viewport.forEach { cache.resultAt(it) } }
        val work = cache.linesTokenizedForDisplay - before
        // Before: each line walked back 50 lines on every frame, 10 x 45 x 51 = 22,950 lines.
        val bound = viewport.count() + IncrementalTokenizer.HEURISTIC_LOOKBACK
        assertTrue("$work lines tokenised for $FRAMES frames", work <= bound)
    }

    @Test
    fun `an edit drops guesses, so the next frame guesses again from the new text`() {
        val doc = TestDoc("a\nb\nc")
        val cache = IncrementalTokenizer(doc)
        assertEquals(LineKind.PARAGRAPH, cache.resultAt(2).kind)
        doc.edit(cache, 0, 0, "```\n")
        assertEquals(LineKind.CODE, cache.tokenizeHeuristically(3).kind)
    }

    @Test
    fun `the tree's line source never copies more than the tokenizer reads (PERF-16)`() {
        val long = "x".repeat(LineTokenizer.MAX_TOKENIZED_COLUMNS * 5)
        val tree = PieceTree.of("# Title\n$long\nend")
        val source = tree.asLineSource()
        assertEquals(LineTokenizer.MAX_TOKENIZED_COLUMNS, source.lineText(1).length)
        assertEquals(
            LineTokenizer.MAX_TOKENIZED_COLUMNS,
            tree
                .snapshot()
                .asLineSource()
                .lineText(1)
                .length,
        )
        assertEquals("end", source.lineText(2).toString())
    }

    private companion object {
        const val FRAMES = 10
        const val HUGE_LINES = 200_000
        const val BREAKS = 200
    }
}
