package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineLayoutTest {
    /** A window of [chars] starting at [start], with every char [advance] wide except where overridden. */
    private fun layout(
        lineLength: Int,
        start: Int,
        chars: String,
        advance: Float = 10f,
        advances: FloatArray? = null,
    ): LineLayout {
        val widths = advances ?: FloatArray(chars.length) { advance }
        val positions = FloatArray(chars.length + 1)
        positions[0] = start * 10f
        for (i in chars.indices) positions[i + 1] = positions[i] + widths[i]
        return LineLayout(lineLength, start, chars.toCharArray(), positions, charWidth = 10f)
    }

    @Test
    fun `a short line is complete and measured exactly`() {
        val line = layout(5, 0, "hello")
        assertTrue(line.isComplete)
        assertEquals(50f, line.width)
        assertEquals(30f, line.xOf(3))
    }

    @Test
    fun `columns outside the window are estimated as monospace cells`() {
        val line = layout(lineLength = 10_000, start = 4_000, chars = "x".repeat(2_000), advance = 12f)
        assertFalse(line.isComplete)
        assertEquals(1_000f, line.xOf(100))
        // Inside the window: measured from the estimated start.
        assertEquals(40_000f + 12f * 10, line.xOf(4_010))
        // After the window: its measured end plus estimates.
        assertEquals(40_000f + 12f * 2_000 + 10f * 5, line.xOf(6_005))
        assertEquals(line.xOf(10_000), line.width)
    }

    @Test
    fun `tap maps to the nearest boundary`() {
        val line = layout(4, 0, "abcd")
        assertEquals(0, line.columnAt(4f))
        assertEquals(1, line.columnAt(6f))
        assertEquals(2, line.columnAt(24f))
        assertEquals(4, line.columnAt(39f))
    }

    @Test
    fun `tap past the end of the line lands at its end`() {
        assertEquals(4, layout(4, 0, "abcd").columnAt(5_000f))
        assertEquals(0, layout(4, 0, "abcd").columnAt(-20f))
    }

    @Test
    fun `tap uses measured widths for wide glyphs`() {
        // "a中b": the CJK char is two cells wide.
        val line = layout(3, 0, "a中b", advances = floatArrayOf(10f, 20f, 10f))
        assertEquals(30f, line.xOf(2))
        assertEquals(2, line.columnAt(29f))
        assertEquals(1, line.columnAt(19f))
    }

    @Test
    fun `tap never lands between the halves of a surrogate pair`() {
        val emoji = "😀"
        val line = layout(3, 0, "a$emoji", advances = floatArrayOf(10f, 20f, 0f))
        // Boundary 2 sits inside the pair; the nearest legal one is 1.
        assertEquals(1, line.columnAt(29f))
        assertEquals(3, line.columnAt(31f))
    }

    @Test
    fun `tap before or after a window estimates the column`() {
        val line = layout(lineLength = 10_000, start = 4_000, chars = "x".repeat(2_000))
        assertEquals(123, line.columnAt(1_230f))
        assertEquals(7_000, line.columnAt(70_000f))
    }

    @Test
    fun `short lines are laid out whole`() {
        assertEquals(0, LineWindow.startFor(lineLength = 2_000, centreColumn = 1_500))
    }

    @Test
    fun `long line windows snap and contain the viewport`() {
        val start = LineWindow.startFor(lineLength = 1_000_000, centreColumn = 123_456)
        assertEquals(0, start % LineWindow.SNAP)
        assertTrue(123_456 - LineWindow.SNAP >= start)
        assertTrue(123_456 + LineWindow.SNAP <= start + LineWindow.SIZE)
        // A small scroll keeps the same window, so the cached layout is reused.
        assertEquals(start, LineWindow.startFor(1_000_000, 123_456 + 40))
    }

    @Test
    fun `long line windows clamp at both ends`() {
        assertEquals(0, LineWindow.startFor(1_000_000, 10))
        assertEquals(1_000_000 - LineWindow.SIZE, LineWindow.startFor(1_000_000, 999_990))
    }
}
