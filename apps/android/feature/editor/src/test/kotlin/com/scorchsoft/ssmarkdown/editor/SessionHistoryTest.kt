package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.text.UndoStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryTest {
    private val changes = mutableListOf<LineChange>()

    private fun session(text: String): EditingSession {
        // A typing run window of zero, so each commit is its own undo step.
        val document = TextDocument(PieceTree.of(text), history = UndoStack(typingRunMillis = -1))
        return EditingSession(document).apply {
            listener =
                object : EditingSession.Listener {
                    override fun onTextChanged(change: LineChange) {
                        changes += change
                    }

                    override fun onSelectionSettled() = Unit
                }
        }
    }

    @Test
    fun `undo reverses the edit and reports the lines it touched`() {
        val s = session("one\ntwo\nthree")
        s.setSelection(4, 4)
        s.commitText("x\ny\n", 1)
        changes.clear()
        assertTrue(s.undo())
        assertEquals("one\ntwo\nthree", s.document.text.toString())
        assertEquals(listOf(LineChange(firstLine = 1, removedBreaks = 2, addedBreaks = 0)), changes)
        assertEquals(4, s.caret)
    }

    @Test
    fun `redo re-applies and puts the caret after the text`() {
        val s = session("abc")
        s.setSelection(3, 3)
        s.commitText("\ndef", 1)
        s.undo()
        changes.clear()
        assertTrue(s.redo())
        assertEquals("abc\ndef", s.document.text.toString())
        assertEquals(listOf(LineChange(0, 0, 1)), changes)
        assertEquals(7, s.caret)
    }

    @Test
    fun `undo with nothing to undo says so`() {
        val s = session("abc")
        assertFalse(s.undo())
        assertFalse(s.redo())
    }

    @Test
    fun `undo flushes composition first`() {
        val s = session("")
        s.setComposingText("hel", 1)
        s.undo()
        assertFalse(s.isComposing)
    }

    @Test
    fun `the envelope of several ops covers all of them in the final text`() {
        // "abcdef" -> insert "XY" at 1 -> "aXYbcdef" -> delete 2 chars at 5 -> "aXYbcf"
        val ops = listOf(EditOp(1, "", "XY"), EditOp(5, "de", ""))
        val envelope = EditEnvelope.of(ops)
        assertEquals(1, envelope.start)
        assertEquals(5, envelope.end)
        assertEquals(0, envelope.breakDelta)
    }

    @Test
    fun `an envelope of an op inside an earlier one stays inside it`() {
        val ops = listOf(EditOp(0, "", "hello\nworld"), EditOp(2, "llo", "y"))
        val envelope = EditEnvelope.of(ops)
        assertEquals(0, envelope.start)
        assertEquals(9, envelope.end)
        assertEquals(1, envelope.breakDelta)
        assertEquals(LineChange(0, 0, 1), envelope.lineChange(PieceTree.of("hey\nworld")))
    }
}
