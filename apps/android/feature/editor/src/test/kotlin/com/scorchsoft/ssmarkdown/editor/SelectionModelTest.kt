package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selection direction, word selection, keyboard movement and outside edits on the session. */
class SelectionModelTest {
    private fun session(
        text: String,
        caret: Int = 0,
    ) = EditingSession(TextDocument(PieceTree.of(text))).apply { setSelection(caret, caret) }

    private val words = WordFinder()

    @Test
    fun `a backwards selection keeps its caret at the start`() {
        val s = session("hello world")
        s.setSelection(8, 2)
        assertEquals(2, s.selectionStart)
        assertEquals(8, s.selectionEnd)
        assertEquals(2, s.caret)
        assertEquals(8, s.anchor)
        assertTrue(s.caretAtStart)
    }

    @Test
    fun `shift arrows extend from the anchor and can cross it`() {
        val s = session("abcdef", caret = 3)
        s.moveCaretByGrapheme(forward = true, extend = true)
        s.moveCaretByGrapheme(forward = true, extend = true)
        assertEquals(3 until 5, s.selectionStart until s.selectionEnd)
        repeat(4) { s.moveCaretByGrapheme(forward = false, extend = true) }
        assertEquals(1, s.selectionStart)
        assertEquals(3, s.selectionEnd)
        assertEquals(1, s.caret)
    }

    @Test
    fun `an arrow without shift collapses the selection to its side`() {
        val s = session("abcdef")
        s.setSelection(1, 4)
        s.moveCaretByGrapheme(forward = false)
        assertEquals(1, s.caret)
        assertFalse(s.hasSelection)
        s.setSelection(1, 4)
        s.moveCaretByGrapheme(forward = true)
        assertEquals(4, s.caret)
    }

    @Test
    fun `ctrl arrows move by word`() {
        val s = session("one two  three", caret = 0)
        s.moveCaretByWord(forward = true, words = words)
        assertEquals(3, s.caret)
        s.moveCaretByWord(forward = true, words = words)
        assertEquals(7, s.caret)
        s.moveCaretByWord(forward = false, words = words, extend = true)
        assertEquals(4, s.selectionStart)
        assertEquals(7, s.selectionEnd)
    }

    @Test
    fun `home and end go to the line or the document`() {
        val s = session("first\nsecond line\nthird", caret = 9)
        s.moveCaretToBoundary(end = true, wholeDocument = false)
        assertEquals(17, s.caret)
        s.moveCaretToBoundary(end = false, wholeDocument = false, extend = true)
        assertEquals(6, s.selectionStart)
        assertEquals(17, s.selectionEnd)
        s.moveCaretToBoundary(end = true, wholeDocument = true)
        assertEquals(23, s.caret)
    }

    @Test
    fun `select word and select all`() {
        val s = session("Some **bold** text")
        s.selectWord(8, words)
        assertEquals("bold", s.selectedText().toString())
        s.selectWord(6, words)
        assertEquals("**", s.selectedText().toString())
        s.selectAll()
        assertEquals(0, s.selectionStart)
        assertEquals(18, s.selectionEnd)
    }

    @Test
    fun `an outside edit commits the keyboard's word first`() {
        val s = session("ab ", caret = 3)
        s.setComposingText("wor", 1)
        s.applyEdit(0, 0, "# ")
        assertFalse(s.isComposing)
        assertEquals("# ab wor", s.document.text.toString())
        assertEquals(2, s.caret)
    }

    @Test
    fun `an outside edit can choose the selection afterwards`() {
        val s = session("word", caret = 0)
        s.applyEdit(0, 4, "**word**", selectStart = 2, selectEnd = 6)
        assertEquals(2, s.selectionStart)
        assertEquals(6, s.selectionEnd)
    }
}
