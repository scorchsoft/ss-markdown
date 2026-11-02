package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.text.EditObserver
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.text.UndoStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edge-cases "Undo after typing with a soft keyboard" and "Enter as a key
 * while composing": what a real keyboard's call pattern leaves in the text
 * and in the undo history.
 */
class KeyboardHistoryTest {
    private var now = 0L
    private val ops = mutableListOf<Triple<Int, String, String>>()

    private fun session(text: String): EditingSession {
        val document = TextDocument(PieceTree.of(text), history = UndoStack(clock = { now }))
        document.addEditObserver(
            object : EditObserver {
                override fun afterEdit(op: EditOp) {
                    ops += Triple(op.offset, op.deleted.toString(), op.inserted.toString())
                }
            },
        )
        return EditingSession(document)
    }

    private fun EditingSession.text() = document.text.toString()

    /** What Gboard does for one word: the composing word grows a letter at a time, then is committed with a space. */
    private fun EditingSession.typeWord(word: String) {
        for (i in 1..word.length) {
            now += 100
            setComposingText(word.substring(0, i), 1)
        }
        now += 100
        commitText(word, 1)
        now += 100
        commitText(" ", 1)
    }

    @Test
    fun `one undo removes a word typed through a composing region`() {
        val s = session("Hi ")
        s.setSelection(3, 3)
        s.typeWord("word")
        assertEquals("Hi word ", s.text())
        assertTrue(s.undo())
        assertEquals("Hi ", s.text())
        assertEquals(3, s.caret)
    }

    @Test
    fun `a pause between words makes each word its own step`() {
        val s = session("")
        s.typeWord("one")
        now += 5_000
        s.typeWord("two")
        s.undo()
        assertEquals("one ", s.text())
        s.undo()
        assertEquals("", s.text())
    }

    @Test
    fun `committing the composed word as it stands records nothing`() {
        val s = session("")
        s.setComposingText("word", 1)
        ops.clear()
        s.commitText("word", 1)
        assertEquals(emptyList<Triple<Int, String, String>>(), ops)
        assertFalse(s.isComposing)
        assertEquals(4, s.caret)
    }

    @Test
    fun `autocorrect records only what it changed, and undo puts the typo back`() {
        val s = session("")
        s.setComposingText("teh", 1)
        ops.clear()
        now += 5_000
        s.commitText("the", 1)
        assertEquals(listOf(Triple(1, "eh", "he")), ops)
        s.undo()
        assertEquals("teh", s.text())
    }

    @Test
    fun `a replacement never splits a surrogate pair`() {
        val s = session("a\uD83D\uDE00b")
        ops.clear()
        s.setComposingRegion(0, 4)
        s.commitText("a\uD83D\uDE01b", 1)
        assertEquals(listOf(Triple(1, "\uD83D\uDE00", "\uD83D\uDE01")), ops)
        assertEquals("a\uD83D\uDE01b", s.text())
    }

    @Test
    fun `the selection follows a trimmed replacement exactly as an untrimmed one`() {
        val s = session("xx hello yy")
        s.setComposingRegion(3, 8)
        s.setSelection(8, 8)
        s.setComposingText("help", 1)
        assertEquals("xx help yy", s.text())
        assertEquals(7, s.caret)
        assertEquals(3, s.composingStart)
        assertEquals(7, s.composingEnd)
    }

    @Test
    fun `Enter as a key keeps the composing word`() {
        val s = session("")
        s.setComposingText("hel", 1)
        s.pressEnter()
        assertEquals("hel\n", s.text())
        assertFalse(s.isComposing)
        assertEquals(4, s.caret)
    }

    @Test
    fun `a typed key keeps the composing word`() {
        val s = session("")
        s.setComposingText("hel", 1)
        s.typeKey("!")
        assertEquals("hel!", s.text())
    }
}
