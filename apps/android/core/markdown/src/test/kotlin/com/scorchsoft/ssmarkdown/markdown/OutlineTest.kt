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
    fun `ten thousand headings stay cheap to query and edit`() {
        val lines = (0 until 50_000).map { i -> if (i % 5 == 0) "#".repeat(1 + (i / 5) % 6) + " H$i" else "text $i" }
        val doc = TestDoc(lines.joinToString("\n"))
        val cache = fullyTokenized(doc)
        assertEquals(10_000, cache.outline.size)
        val started = System.nanoTime()
        repeat(1_000) { n ->
            val line = (n * 49) % 50_000
            doc.edit(cache, doc.offsetOf(line), doc.offsetOf(line), "x")
            cache.tokenizeUntil(line + 1, Long.MAX_VALUE / 4)
            cache.outline.breadcrumbAt(line)
            cache.outline.sectionRange(cache.outline.heading(cache.outline.indexAt(line).coerceAtLeast(0)).line)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // Dominated by TestDoc's own O(n) offset maths; the outline's share is microseconds.
        assertTrue("1,000 edits + queries took $elapsedMs ms", elapsedMs < 10_000)
    }
}
