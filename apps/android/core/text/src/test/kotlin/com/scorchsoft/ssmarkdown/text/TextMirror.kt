package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertEquals

/**
 * A [PieceTree] and a naive [StringBuilder] driven in lock step. Every
 * check compares the tree against the obviously-correct reference, which
 * is how VS Code's own tests are written.
 */
class TextMirror(
    initial: String = "",
) {
    val tree: PieceTree = PieceTree.of(initial)
    val reference: StringBuilder = StringBuilder(normalize(initial))

    fun insert(
        offset: Int,
        text: String,
    ): TextMirror {
        tree.insert(offset, text)
        reference.insert(offset, normalize(text))
        return this
    }

    fun delete(
        offset: Int,
        count: Int,
    ): TextMirror {
        tree.delete(offset, count)
        reference.delete(offset, offset + count)
        return this
    }

    /** Text, totals and tree invariants. */
    fun assertText() {
        assertEquals(reference.toString(), tree.toString())
        assertEquals(reference.length, tree.length)
        assertEquals(reference.count { it == '\n' } + 1, tree.lineCount)
        tree.checkInvariants()
    }

    /** Everything, including every line and every line start (VS Code's `testLinesContent` + `testLineStarts`). */
    fun assertAll() {
        assertText()
        val text = reference.toString()
        val lines = text.split('\n')
        var lineStart = 0
        for ((i, line) in lines.withIndex()) {
            assertEquals("line $i", line, tree.lineContent(i))
            assertEquals("start of line $i", lineStart, tree.lineStartOffset(i))
            assertEquals(TextPosition(i, 0), tree.positionAt(lineStart))
            assertEquals(lineStart + line.length, tree.lineEndOffset(i))
            lineStart += line.length + 1
        }
        val snapshot = tree.snapshot()
        assertEquals(text, snapshot.toString())
        assertEquals(tree.lineCount, snapshot.lineCount)
    }

    companion object {
        fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
    }
}
