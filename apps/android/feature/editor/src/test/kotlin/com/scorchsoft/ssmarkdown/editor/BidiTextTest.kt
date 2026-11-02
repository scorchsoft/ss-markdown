package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visual order of mixed-direction lines (M1): which runs are drawn where,
 * where each caret position sits, and that a tap lands back on the column
 * it was drawn for. Every char is [CELL] px wide, so the maths is exact.
 */
class BidiTextTest {
    private val hebrew = "שלום"
    private val arabic = "مرحبا"

    private fun positions(
        text: String,
        start: Float = 0f,
    ) = FloatArray(text.length + 1) { start + it * CELL }

    private fun bidi(text: String): BidiText = checkNotNull(BidiText.of(text.toCharArray(), positions(text)))

    private fun runs(
        layout: BidiText,
        row: Int = 0,
    ): List<Triple<Int, Int, Boolean>> {
        val count = layout.runCount(row)
        return (0 until count).map { Triple(layout.runStart(row, it), layout.runEnd(row, it), layout.isRtl(row, it)) }
    }

    @Test
    fun `left-to-right text never reaches the bidi algorithm`() {
        assertNull(BidiText.of("plain ascii".toCharArray(), positions("plain ascii")))
        val wide = "emoji 👋 and 日本語, accents é"
        assertNull(BidiText.of(wide.toCharArray(), positions(wide)))
        assertNotNull(BidiText.of("a $hebrew".toCharArray(), positions("a $hebrew")))
    }

    @Test
    fun `a mixed line draws its runs left to right, the Hebrew one reversed`() {
        // "ab שלום cd": on screen "ab םולש cd".
        val line = bidi("ab $hebrew cd")
        assertEquals(listOf(Triple(0, 3, false), Triple(3, 7, true), Triple(7, 10, false)), runs(line))
        // The first Hebrew letter is drawn at the right of its word, the last at the left.
        assertEquals(60f, line.charLeft(3))
        assertEquals(30f, line.charLeft(6))
        assertEquals(70f, line.charLeft(7))
    }

    @Test
    fun `each caret position sits at one visual boundary`() {
        val line = bidi("ab $hebrew cd")
        // Where runs meet, the more deeply embedded (Hebrew) side owns the caret.
        val expected = listOf(0f, 10f, 20f, 70f, 60f, 50f, 40f, 30f, 80f, 90f, 100f)
        assertEquals(expected, (0..10).map { line.caretX(0, it) })
    }

    @Test
    fun `a tap lands on the column drawn there`() {
        for (text in listOf("ab $hebrew cd", hebrew, "$arabic 123 abc", "x $hebrew 42 $arabic y")) {
            val line = bidi(text)
            for (column in 0..text.length) {
                val x = line.caretX(0, column)
                val back = line.columnAt(0, x, text.length)
                assertEquals("'$text' column $column at $x", x, line.caretX(0, back))
            }
        }
    }

    @Test
    fun `numbers inside right-to-left text stay left to right`() {
        // "שלום 123": on screen "123 םולש".
        val line = bidi("$hebrew 123")
        val digits = runs(line).single { it.first == 5 }
        assertEquals(Triple(5, 8, false), digits)
        assertEquals(0f, line.charLeft(5))
        assertEquals(20f, line.charLeft(7))
        assertEquals(70f, line.charLeft(0))
    }

    @Test
    fun `an Arabic line is a mirror of its logical order`() {
        val line = bidi(arabic)
        assertEquals(listOf(Triple(0, 5, true)), runs(line))
        assertEquals((0..5).map { 50f - it * CELL }, (0..5).map { line.caretX(0, it) })
    }

    @Test
    fun `the origin moves the whole line`() {
        val text = "ab $hebrew"
        val shifted = checkNotNull(BidiText.of(text.toCharArray(), positions(text, start = 500f)))
        assertEquals(500f + 60f, shifted.charLeft(3))
        assertEquals(500f + bidi(text).caretX(0, 4), shifted.caretX(0, 4))
    }

    @Test
    fun `wrapped rows are reordered one by one`() {
        // Two rows: "ab שלום " and "cd עולם"; each row is reordered on its own.
        val text = "ab $hebrew cd עולם"
        val chars = text.toCharArray()
        val line = checkNotNull(BidiText.of(chars, positions(text), rowStarts = intArrayOf(0, 8)))
        assertEquals(2, line.rowCount)
        assertEquals(listOf(Triple(0, 3, false), Triple(3, 7, true), Triple(7, 8, false)), runs(line, 0))
        assertEquals(listOf(Triple(8, 11, false), Triple(11, 15, true)), runs(line, 1))
        // The second row starts at its own left edge.
        assertEquals(0f, line.charLeft(8))
        assertEquals(60f, line.charLeft(11))
        assertEquals(30f, line.charLeft(14))
    }

    @Test
    fun `caret movement walks the same order the layout draws`() {
        for (text in listOf("ab $hebrew cd", hebrew, "$hebrew 123 abc", "x $arabic 42 y")) {
            val line = bidi(text)
            val drawn = (0..text.length).sortedWith(compareBy({ line.caretX(0, it) }, { it }))
            assertEquals(text, drawn, BidiCaret.visualOrder(text))
        }
    }

    @Test
    fun `a selection covers the right pixels in each run`() {
        val line = bidi("ab $hebrew cd")
        // Columns 1..5 are "b", " ", "ש", "ל": b and the space on the left, ש and ל at the right of the word.
        val pieces = mutableListOf<Pair<Float, Float>>()
        line.forEachPiece(0, 1, 5) { _, _, _, left, right -> pieces += left to right }
        assertEquals(listOf(10f to 30f, 50f to 70f), pieces)
        assertTrue(pieces.all { it.first < it.second })
    }

    private companion object {
        const val CELL = 10f
    }
}
