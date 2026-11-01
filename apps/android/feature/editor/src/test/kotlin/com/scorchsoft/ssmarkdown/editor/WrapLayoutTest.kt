package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Line breaking over synthetic monospace advances (10 px a cell), so the maths is exact. */
class WrapLayoutTest {
    private fun layout(
        text: String,
        width: Float,
        lineLength: Int = text.length,
    ): WrapLayout {
        val chars = text.toCharArray()
        val positions = FloatArray(chars.size + 1) { it * CELL }
        return WrapLayout(lineLength, chars, positions, WrapLayout.breakRows(chars, positions, width), CELL, width)
    }

    @Test
    fun `rows break after the last space that fits`() {
        val wrapped = layout("the quick brown fox", width = 80f)
        assertEquals(4, wrapped.rowCount)
        assertEquals(listOf(0, 4, 10, 16), (0 until 4).map(wrapped::rowStart))
        assertEquals(19, wrapped.rowEnd(3))
    }

    @Test
    fun `a word wider than the row breaks mid-word`() {
        val chars = "abcdefghij".toCharArray()
        val positions = FloatArray(chars.size + 1) { it * CELL }
        assertArrayEquals(intArrayOf(0, 4, 8), WrapLayout.breakRows(chars, positions, 40f))
    }

    @Test
    fun `trailing spaces hang instead of starting a row`() {
        val wrapped = layout("abcd    efgh", width = 40f)
        assertEquals(2, wrapped.rowCount)
        assertEquals(8, wrapped.rowStart(1))
    }

    @Test
    fun `a column on a wrap boundary starts the next row`() {
        val wrapped = layout("aaaa bbbb", width = 50f)
        assertEquals(0, wrapped.rowOf(4))
        assertEquals(1, wrapped.rowOf(5))
        assertEquals(0f, wrapped.xOf(5), 0f)
        assertEquals(50f, wrapped.xInRow(0, 5), 0f)
    }

    @Test
    fun `x and column are inverses on every row`() {
        val wrapped = layout("lorem ipsum dolor sit amet consectetur", width = 120f)
        for (column in 0..38) {
            val row = wrapped.rowOf(column)
            assertEquals("column $column", column, wrapped.columnAt(row, wrapped.xOf(column)))
        }
    }

    @Test
    fun `past the end of a wrapped row the caret stays on that row`() {
        val wrapped = layout("aaaa bbbb", width = 50f)
        assertEquals(4, wrapped.columnAt(0, 500f))
        assertEquals(9, wrapped.columnAt(1, 500f))
    }

    @Test
    fun `a line longer than the measured window gets estimated rows`() {
        val wrapped = layout("x".repeat(100), width = 100f, lineLength = 1_100)
        assertEquals(10 + 100, wrapped.rowCount)
        assertEquals(100, wrapped.rowStart(10))
        assertEquals(15, wrapped.rowOf(150))
        assertEquals(1_100, wrapped.rowEnd(wrapped.rowCount - 1))
        assertEquals(50f, wrapped.xOf(155), 0f)
        assertEquals(155, wrapped.columnAt(15, 50f))
    }

    @Test
    fun `an empty line is one row`() {
        val wrapped = layout("", width = 100f)
        assertEquals(1, wrapped.rowCount)
        assertEquals(0, wrapped.columnAt(0, 30f))
    }

    private companion object {
        const val CELL = 10f
    }
}
