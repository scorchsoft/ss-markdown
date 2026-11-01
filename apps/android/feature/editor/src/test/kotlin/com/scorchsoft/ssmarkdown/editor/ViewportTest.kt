package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    private val viewport = Viewport(lineHeight = 20)

    @Test
    fun `visible lines at the top are the first screenful`() {
        assertEquals(0..9, viewport.visibleLines(scrollY = 0, viewportHeight = 200, lineCount = 1_000))
    }

    @Test
    fun `a partly visible line at either edge counts as visible`() {
        assertEquals(0..10, viewport.visibleLines(scrollY = 10, viewportHeight = 200, lineCount = 1_000))
    }

    @Test
    fun `visible lines stop at the last line`() {
        assertEquals(995..999, viewport.visibleLines(scrollY = 19_900, viewportHeight = 200, lineCount = 1_000))
    }

    @Test
    fun `an empty document has no visible lines`() {
        assertTrue(viewport.visibleLines(0, 200, 0).isEmpty())
    }

    @Test
    fun `visible range is arithmetic, not a walk, at two million lines`() {
        val lines = 2_000_000
        val scroll = viewport.maxScrollY(lines, 2_000)
        assertEquals(1_999_900..1_999_999, viewport.visibleLines(scroll, 2_000, lines))
    }

    @Test
    fun `render margin leans a full screen ahead when scrolling down`() {
        val range =
            viewport.renderLines(
                scrollY = 2_000,
                viewportHeight = 200,
                lineCount = 1_000,
                ScrollDirection.TOWARDS_END,
            )
        // A quarter screen (50 px, 2.5 lines) behind, a whole screen (10 lines) ahead.
        assertEquals(97, range.first)
        assertEquals(119, range.last)
    }

    @Test
    fun `render margin leans a full screen ahead when scrolling up`() {
        val range = viewport.renderLines(2_000, 200, 1_000, ScrollDirection.TOWARDS_START)
        assertEquals(90, range.first)
        assertEquals(112, range.last)
    }

    @Test
    fun `render margin is even at rest and clamps to the document`() {
        assertEquals(95..114, viewport.renderLines(2_000, 200, 1_000, ScrollDirection.IDLE))
        assertEquals(0..14, viewport.renderLines(0, 200, 1_000, ScrollDirection.IDLE))
    }

    @Test
    fun `max scroll puts the last line at the bottom and is zero for a short document`() {
        assertEquals(19_800, viewport.maxScrollY(lineCount = 1_000, viewportHeight = 200))
        assertEquals(0, viewport.maxScrollY(lineCount = 3, viewportHeight = 200))
    }

    @Test
    fun `scroll to reveal moves the least distance`() {
        assertEquals(100, viewport.scrollToReveal(line = 5, scrollY = 1_000, viewportHeight = 200))
        assertEquals(1_000, viewport.scrollToReveal(line = 55, scrollY = 1_000, viewportHeight = 200))
        assertEquals(1_020, viewport.scrollToReveal(line = 60, scrollY = 1_000, viewportHeight = 200))
    }

    @Test
    fun `line at y clamps to the document`() {
        assertEquals(0, viewport.lineAt(-50, 10))
        assertEquals(9, viewport.lineAt(10_000, 10))
        assertEquals(3, viewport.lineAt(79, 10))
    }
}
