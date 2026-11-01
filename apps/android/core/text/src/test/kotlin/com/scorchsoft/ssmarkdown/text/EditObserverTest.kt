package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditObserverTest {
    private class Recorder(
        private val document: TextDocument,
    ) : EditObserver {
        val events = mutableListOf<String>()

        override fun beforeEdit(op: EditOp) {
            // The text still holds the removed chars: this is "the text before".
            assertEquals(op.deleted.toString(), document.text.getText(op.offset, op.offset + op.deleted.length))
            events += "before ${op.offset} -${op.deleted} +${op.inserted} @${document.version}"
        }

        override fun afterEdit(op: EditOp) {
            events += "after @${document.version}"
        }
    }

    @Test
    fun `every applied op is told before and after, with its exact chars`() {
        val document = TextDocument(PieceTree.of("hello world"))
        val recorder = Recorder(document).also(document::addEditObserver)

        document.replace(6, 5, "there")

        assertEquals(listOf("before 6 -world +there @0", "after @1"), recorder.events)
    }

    @Test
    fun `undo and redo are told as the ops they actually apply, in order`() {
        val document = TextDocument(PieceTree.of("abc"))
        document.beginEditGroup()
        document.replace(0, 0, "1")
        document.replace(4, 0, "2")
        document.endEditGroup()
        val recorder = Recorder(document).also(document::addEditObserver)

        document.undo()
        document.redo()

        assertEquals(
            listOf(
                "before 4 -2 + @2",
                "after @3",
                "before 0 -1 + @3",
                "after @4",
                "before 0 - +1 @4",
                "after @5",
                "before 4 - +2 @5",
                "after @6",
            ),
            recorder.events,
        )
    }

    @Test
    fun `replaying what an observer heard rebuilds the text exactly`() {
        val document = TextDocument(PieceTree.of("line one\nline two\n"))
        val mirror = StringBuilder(document.text.toString())
        document.addEditObserver(
            object : EditObserver {
                override fun beforeEdit(op: EditOp) {
                    mirror.replace(op.offset, op.offset + op.deleted.length, op.inserted.toString())
                }
            },
        )

        document.replace(5, 3, "1")
        document.replace(0, 0, "# ")
        document.replace(document.text.length, 0, "three\r\n")
        document.undo()
        document.replace(3, 4, "")

        assertEquals(document.text.toString(), mirror.toString())
    }

    @Test
    fun `a removed observer hears nothing, and no-ops are never told`() {
        val document = TextDocument(PieceTree.of("x"))
        val recorder = Recorder(document)
        document.addEditObserver(recorder)
        document.addEditObserver(recorder)
        document.replace(0, 0, "")
        document.replace(0, 0, "y")
        document.removeEditObserver(recorder)
        document.replace(0, 0, "z")

        assertEquals(2, recorder.events.size)
        assertTrue(recorder.events.first().startsWith("before 0"))
    }
}
