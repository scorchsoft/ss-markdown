package com.scorchsoft.ssmarkdown.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The block index behind the Navigator outline, the breadcrumb and Copy section. */
class OutlineTest {
    private val text =
        """
        intro
        # How we work
        text
        ## Code review
        review text
        ### Checklist
        - [ ] item
        ## Releases
        Setext *two*
        ------------
        # Appendix
        last
        """.trimIndent()

    private val outline = fullyTokenized(TestDoc(text)).outline

    @Test
    fun `headings come in document order with readable text`() {
        assertEquals(
            listOf(
                Heading(1, 1, "How we work"),
                Heading(3, 2, "Code review"),
                Heading(5, 3, "Checklist"),
                Heading(7, 2, "Releases"),
                Heading(8, 2, "Setext two"),
                Heading(10, 1, "Appendix"),
            ),
            outline.headings(),
        )
    }

    @Test
    fun `heading text drops markup`() {
        val cache = fullyTokenized(TestDoc("## **Save** [flow](#x) `now` \\# ##"))
        assertEquals("Save flow now #", cache.outline.heading(0).text)
    }

    @Test
    fun `a section runs to the next heading of the same or a higher level`() {
        assertEquals(1..9, outline.sectionRange(1))
        assertEquals(7..7, outline.sectionRange(7))
        assertEquals(3..6, outline.sectionRange(3))
        assertEquals(5..6, outline.sectionRange(5))
        assertEquals(8..9, outline.sectionRange(8))
        assertEquals(10..11, outline.sectionRange(10))
        assertNull(outline.sectionRange(2))
    }

    @Test
    fun `the breadcrumb is the chain of enclosing headings`() {
        assertEquals(emptyList<String>(), outline.breadcrumbAt(0).map { it.text })
        assertEquals(listOf("How we work"), outline.breadcrumbAt(2).map { it.text })
        assertEquals(listOf("How we work", "Code review", "Checklist"), outline.breadcrumbAt(6).map { it.text })
        assertEquals(listOf("How we work", "Setext two"), outline.breadcrumbAt(9).map { it.text })
        assertEquals(listOf("Appendix"), outline.breadcrumbAt(11).map { it.text })
    }

    @Test
    fun `skipped levels still nest under the nearest higher heading`() {
        val cache = fullyTokenized(TestDoc("### deep\n# top\n#### four\n## two\nx"))
        assertEquals(listOf("top", "two"), cache.outline.breadcrumbAt(4).map { it.text })
        assertEquals(-1, cache.outline.parentOf(0))
        assertEquals(1, cache.outline.parentOf(2))
    }

    @Test
    fun `the outline follows edits`() {
        val doc = TestDoc(text)
        val cache = fullyTokenized(doc)
        doc.edit(cache, doc.offsetOf(3), doc.offsetOf(3) + 2, "#")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(Heading(3, 1, "Code review"), cache.outline.heading(1))
        assertEquals(listOf("Code review", "Checklist"), cache.outline.breadcrumbAt(6).map { it.text })
        doc.edit(cache, 0, 0, "```\n")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(0, cache.outline.size)
    }

    @Test
    fun `a detached copy follows edits without its tokenizer, and never moves the original`() {
        val copy = outline.copy()
        // Enter in the middle of "## Code review" (line 3): the heading stays on line 3, later ones move down.
        copy.followEdit(3, 1, 2)
        assertEquals(listOf(1, 3, 6, 8, 9, 11), copy.headings().map { it.line })
        assertEquals(listOf(1, 3, 5, 7, 8, 10), outline.headings().map { it.line })
        // Lines 5-7 become one: line 5 keeps its place, the Checklist heading on line 6 goes, the rest move up.
        copy.followEdit(5, 3, 1)
        assertEquals(listOf(1, 3, 6, 7, 9), copy.headings().map { it.line })
        assertEquals(
            listOf("How we work", "Code review", "Releases", "Setext two", "Appendix"),
            copy.headings().map {
                it.text
            },
        )
        assertEquals(9..10, copy.sectionRange(9))
    }

    @Test(expected = IllegalStateException::class)
    fun `a tokenizer's own outline refuses to follow edits by hand`() {
        outline.followEdit(0, 1, 1)
    }

    @Test
    fun `ten thousand headings stay cheap to query and edit`() {
        val lines = (0 until 50_000).map { i -> if (i % 5 == 0) "#".repeat(1 + (i / 5) % 6) + " H$i" else "text $i" }
        val doc = TestDoc(lines.joinToString("\n"))
        val cache = fullyTokenized(doc)
        assertEquals(10_000, cache.outline.size)
        val tokenizedBefore = cache.linesTokenized
        val derivedBefore = cache.outline.derivations
        repeat(EDITS) { n ->
            val line = (n * 49) % 50_000
            doc.edit(cache, doc.offsetOf(line), doc.offsetOf(line), "x")
            cache.tokenizeUntil(line + 1, Long.MAX_VALUE / 4)
            repeat(QUERIES_PER_EDIT) {
                cache.outline.breadcrumbAt(line)
                cache.outline.sectionRange(cache.outline.heading(cache.outline.indexAt(line).coerceAtLeast(0)).line)
            }
        }
        // Work, not wall-clock time (CC-F): an edit re-reads a line or two, and the parent/section pass
        // runs at most once per edit however many queries follow it, never once per query.
        val tokenized = cache.linesTokenized - tokenizedBefore
        assertTrue("$tokenized lines tokenised for $EDITS edits", tokenized <= EDITS * 3L)
        val derived = cache.outline.derivations - derivedBefore
        assertTrue("$derived derivations for $EDITS edits", derived <= EDITS)
    }

    private companion object {
        const val EDITS = 1_000
        const val QUERIES_PER_EDIT = 5
    }
}
