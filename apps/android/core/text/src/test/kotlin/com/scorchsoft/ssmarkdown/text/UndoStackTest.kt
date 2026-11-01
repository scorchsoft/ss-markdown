package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {
    private var now = 0L
    private val history = UndoStack(maxBytes = 10_000, typingRunMillis = 1_000, clock = { now })
    private val document = TextDocument(PieceTree.of("Hello"), history = history)

    private fun type(text: String) {
        for (c in text) {
            document.replace(document.text.length, 0, c.toString())
            now += 100
        }
    }

    @Test
    fun `a burst of typing undoes as one step`() {
        type(" world")
        document.undo()
        assertEquals("Hello", document.text.toString())
        assertFalse(document.canUndo)
        document.redo()
        assertEquals("Hello world", document.text.toString())
    }

    @Test
    fun `a pause starts a new step`() {
        type(" big")
        now += 5_000
        type(" world")
        document.undo()
        assertEquals("Hello big", document.text.toString())
    }

    @Test
    fun `a cursor jump breaks the run`() {
        type("!")
        document.breakTypingRun()
        type("?")
        document.undo()
        assertEquals("Hello!", document.text.toString())
    }

    @Test
    fun `backspacing is a run of its own`() {
        type("abc")
        repeat(3) { document.replace(document.text.length - 1, 1, "") }
        assertEquals("Hello", document.text.toString())
        document.undo()
        assertEquals("Helloabc", document.text.toString())
        document.undo()
        assertEquals("Hello", document.text.toString())
    }

    @Test
    fun `a compound edit undoes as one step, and groups nest`() {
        document.beginEditGroup()
        document.replace(0, 0, "**")
        document.beginEditGroup()
        document.replace(document.text.length, 0, "**")
        document.endEditGroup()
        document.endEditGroup()
        assertEquals("**Hello**", document.text.toString())
        val group = document.undo()
        assertEquals(2, group?.ops?.size)
        assertEquals("Hello", document.text.toString())
    }

    @Test
    fun `a new edit clears redo`() {
        type("!")
        document.undo()
        assertTrue(document.canRedo)
        type("?")
        assertFalse(document.canRedo)
        assertNull(document.redo())
    }

    @Test
    fun `the oldest steps are dropped past the byte budget but the newest is kept`() {
        repeat(20) {
            document.breakTypingRun()
            document.replace(0, 0, "x".repeat(400))
        }
        assertTrue(history.retainedBytes <= 10_000)
        var steps = 0
        while (document.undo() != null) steps++
        assertTrue(steps in 1 until 20)
        val huge = UndoStack(maxBytes = 100)
        huge.record(EditOp(0, "", "y".repeat(1_000)))
        assertTrue(huge.canUndo)
    }

    @Test
    fun `undoing a huge delete relinks its pieces instead of copying`() {
        val text = PieceTree.of("z".repeat(1_000_000))
        val big = TextDocument(text)
        val op = big.replace(0, text.length, "")
        assertTrue(op.deleted is Snapshot)
        assertEquals(0, text.length)
        big.undo()
        assertEquals(1_000_000, text.length)
        assertTrue(text.pieceCount <= 16)
    }
}
