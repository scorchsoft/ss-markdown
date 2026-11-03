package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fast-scroll thumb's line-index maths over a [HeightMap] (docs/ux-spec.md, "Fast scroll"). */
class FastScrollMappingTest {
    private fun map(lines: Int = LINES) = HeightMap(lines, ROW)

    @Test
    fun `the top position is the line at the top plus the share of it scrolled past`() {
        val h = map()
        assertEquals(0.0, h.topPosition(0), EPS)
        assertEquals(10.5, h.topPosition(10 * ROW + ROW / 2), EPS)
        h.setRows(3, 4)
        // Line 3 is now four rows: a quarter of the way down it.
        assertEquals(3.25, h.topPosition(3 * ROW + ROW), EPS)
    }

    @Test
    fun `the thumb fraction is 0 at the top, 1 at the very bottom, and 0 when nothing scrolls`() {
        val h = map()
        val end = h.endPosition(VIEWPORT)
        assertEquals(0f, h.lineFraction(0, end), 0f)
        assertEquals(1f, h.lineFraction(h.maxScrollY(VIEWPORT), end), 0f)
        val short = map(lines = 3)
        assertEquals(0f, short.lineFraction(0, short.endPosition(VIEWPORT)), 0f)
    }

    @Test
    fun `the visible fraction is the viewport over the content`() {
        assertEquals(VIEWPORT.toFloat() / (LINES * ROW), map().visibleFraction(VIEWPORT), 1e-6f)
        assertEquals(1f, map(lines = 3).visibleFraction(VIEWPORT), 0f)
    }

    @Test
    fun `measuring lines changes pixels but not which line a position names`() {
        val h = map()
        val before = h.topPosition(h.lineTop(500).toInt())
        for (line in 0 until 400 step 3) h.setRows(line, 1 + line % 7)
        assertEquals(before, h.topPosition(h.lineTop(500).toInt()), EPS)
    }

    private companion object {
        const val LINES = 10_000
        const val ROW = 40
        const val VIEWPORT = 1_000
        const val EPS = 1e-9
    }
}
