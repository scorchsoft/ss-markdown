package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTextTest {
    private val a11y = AccessibilityText()

    private fun session(
        text: String,
        caret: Int,
    ) = EditingSession(TextDocument(PieceTree.of(text))).apply { setSelection(caret, caret) }

    @Test
    fun `the node text is a window around the caret, never the whole file`() {
        val s = session("x".repeat(1_000_000), caret = 600_000)
        val window = a11y.nodeWindow(s)
        assertEquals(AccessibilityText.MAX_NODE_CHARS, window.text.length)
        assertTrue(600_000 - window.start in 0..window.text.length)
    }

    @Test
    fun `a small file is its own window`() {
        val s = session("short", caret = 2)
        assertEquals(TextWindow(0, "short"), a11y.nodeWindow(s))
    }

    @Test
    fun `a line node is capped`() {
        val text = PieceTree.of("y".repeat(50_000) + "\nshort")
        assertEquals(AccessibilityText.MAX_LINE_CHARS, a11y.lineText(text, 0).length)
        assertEquals("short", a11y.lineText(text, 1).toString())
    }

    @Test
    fun `character steps cross whole graphemes`() {
        val flag = "🇬🇧" // One flag: four chars, one grapheme.
        val s = session("a${flag}b", caret = 1)
        assertEquals(1 until 5, a11y.step(s, 1, Granularity.CHARACTER, forward = true))
        assertEquals(1 until 5, a11y.step(s, 5, Granularity.CHARACTER, forward = false))
    }

    @Test
    fun `word and line steps`() {
        val s = session("one two\nthree four", caret = 0)
        assertEquals(0 until 3, a11y.step(s, 0, Granularity.WORD, forward = true))
        assertEquals(0 until 7, a11y.step(s, 0, Granularity.LINE, forward = true))
        assertEquals(7 until 18, a11y.step(s, 7, Granularity.LINE, forward = true))
        assertEquals(8 until 12, a11y.step(s, 12, Granularity.LINE, forward = false))
    }

    @Test
    fun `no step past the document's edges`() {
        val s = session("ab", caret = 0)
        assertNull(a11y.step(s, 0, Granularity.CHARACTER, forward = false))
        assertNull(a11y.step(s, 2, Granularity.WORD, forward = true))
    }
}
