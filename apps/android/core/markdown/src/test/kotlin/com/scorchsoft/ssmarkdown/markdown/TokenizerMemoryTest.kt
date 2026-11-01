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
        assertTrue("$perLine bytes a line", perLine < 16.0)
        assertEquals(20_000, cache.outline.size)
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
    }
}
