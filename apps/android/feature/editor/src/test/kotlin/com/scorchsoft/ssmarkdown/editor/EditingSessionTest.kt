package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingSessionTest {
    private class Recorder : EditingSession.Listener {
        val changes = mutableListOf<LineChange>()
        var settled = 0

        override fun onTextChanged(change: LineChange) {
            changes += change
        }

        override fun onSelectionSettled() {
            settled++
        }
    }

    private fun session(
        text: String,
        caret: Int = text.length,
    ): EditingSession = EditingSession(TextDocument(PieceTree.of(text))).apply { setSelection(caret, caret) }

    private val EditingSession.text: String get() = document.text.toString()

    @Test
    fun `commit inserts at the caret and moves past it`() {
        val s = session("helo", caret = 3)
        s.commitText("l", 1)
        assertEquals("hello", s.text)
        assertEquals(4, s.caret)
    }

    @Test
    fun `commit honours newCursorPosition either side of the text`() {
        val before = session("ab", caret = 1)
        before.commitText("XY", 0)
        assertEquals(1, before.caret)
        val after = session("ab", caret = 1)
        after.commitText("XY", 2)
        assertEquals(4, after.caret)
    }

    @Test
    fun `commit replaces a selection`() {
        val s = session("hello world")
        s.setSelection(6, 11)
        s.commitText("there", 1)
        assertEquals("hello there", s.text)
    }

    @Test
    fun `composing text replaces itself until finished`() {
        val s = session("I ")
        s.setComposingText("h", 1)
        s.setComposingText("he", 1)
        s.setComposingText("hel", 1)
        assertEquals("I hel", s.text)
        assertEquals(2, s.composingStart)
        assertEquals(5, s.composingEnd)
        s.commitText("help", 1)
        assertEquals("I help", s.text)
        assertFalse(s.isComposing)
        assertEquals(6, s.caret)
    }

    @Test
    fun `finishing composition keeps the text`() {
        val s = session("")
        s.setComposingText("word", 1)
        s.finishComposingText()
        assertEquals("word", s.text)
        assertFalse(s.isComposing)
    }

    @Test
    fun `composing region can be set over existing text and then replaced`() {
        val s = session("teh cat", caret = 3)
        s.setComposingRegion(0, 3)
        s.setComposingText("the", 1)
        assertEquals("the cat", s.text)
    }

    @Test
    fun `delete surrounding removes both sides and leaves the caret between`() {
        val s = session("abcdef", caret = 3)
        s.deleteSurroundingText(2, 1)
        assertEquals("aef", s.text)
        assertEquals(1, s.caret)
    }

    @Test
    fun `delete surrounding never splits a surrogate pair`() {
        val emoji = "😀"
        val s = session("a$emoji")
        s.deleteSurroundingText(1, 0)
        assertEquals("a", s.text)
    }

    @Test
    fun `delete surrounding clamps at the document edges`() {
        val s = session("ab", caret = 1)
        s.deleteSurroundingText(10, 10)
        assertEquals("", s.text)
    }

    @Test
    fun `backspace deletes a whole grapheme`() {
        val family = "👨‍👩‍👧"
        val s = session("x$family")
        s.pressBackspace()
        assertEquals("x", s.text)
        s.pressBackspace()
        assertEquals("", s.text)
        s.pressBackspace()
        assertEquals("", s.text)
    }

    @Test
    fun `enter inserts a line break and reports the new line`() {
        val recorder = Recorder()
        val s = session("ab\ncd", caret = 4)
        s.listener = recorder
        s.pressEnter()
        assertEquals("ab\nc\nd", s.text)
        assertEquals(LineChange(firstLine = 1, removedBreaks = 0, addedBreaks = 1), recorder.changes.single())
    }

    @Test
    fun `a CRLF from the keyboard is stored as one break and the caret follows it`() {
        val s = session("ab", caret = 1)
        s.commitText("\r\n", 1)
        assertEquals("a\nb", s.text)
        assertEquals(2, s.caret)
    }

    @Test
    fun `deleting across lines reports the removed breaks`() {
        val recorder = Recorder()
        val s = session("a\nb\nc")
        s.listener = recorder
        s.setSelection(1, 4)
        s.commitText("", 1)
        assertEquals("ac", s.text)
        assertEquals(LineChange(0, 2, 0), recorder.changes.single())
    }

    @Test
    fun `arrow keys move by grapheme and keep the column across lines`() {
        val s = session("abcdef\nxy\nlonger line", caret = 4)
        s.moveCaretByLine(1)
        assertEquals(9, s.caret) // Clamped to the end of "xy".
        s.moveCaretByLine(1)
        assertEquals(12, s.caret) // Column 2 of the third line.
        s.moveCaretByGrapheme(forward = false)
        assertEquals(11, s.caret)
    }

    @Test
    fun `keyboard reads are capped at the 32K window`() {
        val big = "x".repeat(100_000)
        val s = session(big, caret = 50_000)
        assertEquals(EditingSession.MAX_IME_WINDOW, s.textBeforeCursor(Int.MAX_VALUE).length)
        assertEquals(EditingSession.MAX_IME_WINDOW, s.textAfterCursor(1_000_000).length)
        s.setSelection(0, big.length)
        assertEquals(EditingSession.MAX_IME_WINDOW, s.selectedText()!!.length)
        val window = s.textWindow()
        assertEquals(EditingSession.MAX_IME_WINDOW, window.text.length)
        assertTrue(window.start in 0..big.length - window.text.length)
    }

    @Test
    fun `text window is centred on the caret and clamps at the ends`() {
        val s = session("0123456789", caret = 5)
        assertEquals(TextWindow(3, "3456"), s.textWindow(4))
        s.setSelection(0, 0)
        assertEquals(TextWindow(0, "0123"), s.textWindow(4))
        s.setSelection(10, 10)
        assertEquals(TextWindow(6, "6789"), s.textWindow(4))
    }

    @Test
    fun `no selected text for a bare caret`() {
        assertNull(session("abc").selectedText())
    }

    @Test
    fun `selection is reported once, when the outermost batch ends`() {
        val recorder = Recorder()
        val s = session("")
        s.listener = recorder
        s.beginBatchEdit()
        s.beginBatchEdit()
        s.setComposingText("a", 1)
        s.commitText("ab", 1)
        assertTrue(s.endBatchEdit())
        assertEquals(0, recorder.settled)
        assertFalse(s.endBatchEdit())
        assertEquals(1, recorder.settled)
        // Text changes are reported immediately, even inside the batch.
        assertEquals(2, recorder.changes.size)
    }

    @Test
    fun `every edit is undoable through the document`() {
        val s = session("abc")
        s.commitText("d", 1)
        s.document.breakTypingRun()
        s.pressBackspace()
        s.pressBackspace()
        assertEquals("ab", s.text)
        s.document.undo()
        assertEquals("abcd", s.text)
    }
}
