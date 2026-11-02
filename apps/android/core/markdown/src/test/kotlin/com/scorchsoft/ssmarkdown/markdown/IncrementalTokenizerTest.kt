package com.scorchsoft.ssmarkdown.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Convergence, budgets, heuristics and the setext look-back of the incremental cache. */
class IncrementalTokenizerTest {
    private fun sampleDoc(lines: Int): TestDoc =
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
    fun `typing inside a paragraph re-tokenises one line`() {
        val doc = sampleDoc(1_000)
        val cache = fullyTokenized(doc)
        val before = cache.linesTokenized
        doc.edit(cache, doc.offsetOf(503) + 4, doc.offsetOf(503) + 4, "x")
        assertTrue(cache.tokenizeUntil(600, Long.MAX_VALUE / 4))
        assertEquals(1L, cache.linesTokenized - before)
        assertTrue(cache.isComplete)
    }

    @Test
    fun `enter inside a paragraph re-tokenises the two halves`() {
        val doc = sampleDoc(1_000)
        val cache = fullyTokenized(doc)
        val before = cache.linesTokenized
        doc.edit(cache, doc.offsetOf(503) + 4, doc.offsetOf(503) + 4, "\n")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(2L, cache.linesTokenized - before)
        assertEquals(1_001, cache.lineCount)
        assertEquals(Heading(511, 2, "Section 510"), cache.outline.heading(51))
    }

    @Test
    fun `opening a fence at the top of a 200k-line file stays within budget`() {
        val doc = sampleDoc(200_000)
        val cache = fullyTokenized(doc)
        assertEquals(20_000, cache.outline.size)

        doc.edit(cache, 0, 0, "```\n")
        val before = cache.linesTokenized
        // Each clock read costs 1 µs, so an 8 ms budget is ~8,000 lines.
        val reached = cache.tokenizeUntil(60, budgetNanos = 8_000_000, clock = SteppingClock(1_000))
        assertTrue(reached)
        assertTrue("viewport pass took ${cache.linesTokenized - before} lines", cache.linesTokenized - before <= 62)
        assertEquals(LineKind.CODE, cache.resultAt(30).kind)
        // Far below the viewport, the stale tokens are the guess until the idle pass arrives.
        assertFalse(cache.isAccurate(150_001))
        assertEquals(LineKind.HEADING, cache.resultAt(150_001).kind)

        var slices = 0
        while (!cache.continueInIdle(budgetNanos = 8_000_000, clock = SteppingClock(1_000))) slices++
        assertTrue("$slices idle slices", slices in 20..30)
        assertEquals(LineKind.CODE, cache.resultAt(150_001).kind)
        assertEquals(0, cache.outline.size)
        assertEquals(200_001L, cache.linesTokenized - before)

        // Closing it again converges straight after the new fence line.
        val reopened = cache.linesTokenized
        doc.edit(cache, doc.offsetOf(2), doc.offsetOf(2), "```\n")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertTrue(cache.isComplete)
        assertEquals(LineKind.HEADING, cache.resultAt(150_002).kind)
        assertEquals(19_999, cache.outline.size) // "Section 0" is still inside the fence.
        assertTrue(cache.linesTokenized - reopened >= 199_998)
    }

    @Test
    fun `every call progresses even with no budget`() {
        val doc = sampleDoc(100)
        val cache = IncrementalTokenizer(doc)
        assertFalse(cache.tokenizeUntil(99, budgetNanos = 0, clock = SteppingClock(1)))
        assertEquals(1, cache.accurateLineCount)
        assertFalse(cache.tokenizeUntil(99, budgetNanos = 0, clock = SteppingClock(1)))
        assertEquals(2, cache.accurateLineCount)
    }

    @Test
    fun `heuristic tokens for pasted lines use the nearest known state`() {
        val doc = TestDoc("```\ncode\n```\nafter")
        val cache = fullyTokenized(doc)
        doc.edit(cache, doc.offsetOf(1), doc.offsetOf(1), "# a\n# b\n# c\n")
        assertFalse(cache.isAccurate(2))
        assertEquals(LineKind.CODE, cache.tokenizeHeuristically(2).kind)
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(LineKind.CODE, cache.resultAt(2).kind)
        assertEquals(0, cache.outline.size)
    }

    @Test
    fun `typing a setext underline restyles the line above, and deleting it restores it`() {
        val doc = TestDoc("Title\nbody")
        val cache = fullyTokenized(doc)
        assertEquals(LineKind.PARAGRAPH, cache.resultAt(0).kind)

        doc.edit(cache, 5, 5, "\n===")
        cache.tokenizeUntil(2, Long.MAX_VALUE / 4)
        assertEquals(LineKind.HEADING, cache.resultAt(0).kind)
        assertEquals(listOf(Heading(0, 1, "Title")), cache.outline.headings())

        doc.edit(cache, 6, 9, "x")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(LineKind.PARAGRAPH, cache.resultAt(0).kind)
        assertEquals(0, cache.outline.size)

        doc.edit(cache, 6, 7, "---")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(listOf(Heading(0, 2, "Title")), cache.outline.headings())

        doc.edit(cache, 5, 9, "")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals("Title\nbody", doc.text)
        assertEquals(LineKind.PARAGRAPH, cache.resultAt(0).kind)
        assertEquals(0, cache.outline.size)
    }

    @Test
    fun `deleting the underline on the last line clears the heading`() {
        val doc = TestDoc("Title\n---")
        val cache = fullyTokenized(doc)
        assertEquals(1, cache.outline.size)
        doc.edit(cache, 5, 9, "")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(LineKind.PARAGRAPH, cache.resultAt(0).kind)
        assertEquals(0, cache.outline.size)
    }

    @Test
    fun `references are indexed incrementally`() {
        val doc = TestDoc("[a]: /one\ntext\n\n[B c]: /two")
        val cache = fullyTokenized(doc)
        assertEquals(0, cache.lineOfRef("a"))
        assertEquals(3, cache.lineOfRef("b C"))
        doc.edit(cache, 0, 0, "\n")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(1, cache.lineOfRef("A"))
        assertEquals(4, cache.lineOfRef("[b   c]"))
        doc.edit(cache, doc.offsetOf(1), doc.offsetOf(2), "")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertFalse(cache.references.contains("a"))
        assertEquals(listOf("b c"), cache.references.labels())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an edit outside the document is rejected`() {
        IncrementalTokenizer(TestDoc("one")).invalidate(1, 1, 1)
    }

    @Test(expected = IllegalStateException::class)
    fun `a missed invalidate is caught before it corrupts the cache`() {
        val doc = TestDoc("one")
        val cache = fullyTokenized(doc)
        doc.replace(0, 0, "\n")
        cache.continueInIdle(1_000)
    }

    private fun IncrementalTokenizer.lineOfRef(label: String): Int = references.lineOf(label)
}
