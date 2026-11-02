package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import com.scorchsoft.ssmarkdown.text.EditOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Sequential ops become one edit relative to the text before them all. */
class CoveringEditTest {
    /** Applies [ops] one after another, as an undo group does. */
    private fun apply(
        text: String,
        ops: List<EditOp>,
    ): String = ops.fold(text) { t, op -> t.replaceRange(op.offset, op.offset + op.deleted.length, op.inserted) }

    private fun check(
        before: String,
        ops: List<EditOp>,
    ) {
        val after = apply(before, ops)
        val edit = CoveringEdit.of(before.length, ops, after) ?: error("no edit")
        assertEquals(after, before.replaceRange(edit.from, edit.to, edit.insert))
    }

    @Test
    fun `two ops far apart cover the span between them`() {
        val ops = listOf(EditOp(10, "k", "K"), EditOp(1, "b", "BB"))
        check("abcdefghijklmnop", ops)
        assertEquals(TextEdit(1, 11, "BBcdefghijK"), CoveringEdit.of(16, ops, apply("abcdefghijklmnop", ops)))
    }

    @Test
    fun `inserts, deletes and overlapping ops all round-trip`() {
        check("one\ntwo\nthree\n", listOf(EditOp(4, "two\n", ""), EditOp(0, "", "zero\n"), EditOp(5, "", "x")))
        check("abc", listOf(EditOp(3, "", "def"), EditOp(0, "abcd", "")))
    }

    @Test
    fun `nothing changed is no edit`() {
        assertNull(CoveringEdit.of(3, listOf(EditOp(1, "", "")), "abc"))
    }
}
