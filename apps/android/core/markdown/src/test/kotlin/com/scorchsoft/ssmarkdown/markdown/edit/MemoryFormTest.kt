package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.text.DecodeResult
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.text.Utf8Decoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Commands run on the editor's real text: a [TextDocument] snapshot of a
 * CRLF file. The file's own line endings come back on save, and only the
 * edited bytes differ.
 */
class MemoryFormTest {
    private fun open(bytes: ByteArray): TextDocument =
        TextDocument.from(Utf8Decoder.decode(bytes) as DecodeResult.Decoded)

    private fun TextDocument.apply(
        command: EditCommand,
        selection: TextSelection,
    ): EditResult {
        val snapshot = snapshot()
        val result = command.apply(snapshot, selection, DocumentStyle.detect(snapshot))
        beginEditGroup()
        for (op in result.ops) replace(op.offset, op.deleted.length, op.inserted)
        endEditGroup()
        return result
    }

    private fun TextDocument.bytes(): ByteArray = ByteArrayOutputStream().also { writeTo(it) }.toByteArray()

    @Test
    fun crlfFileKeepsItsEndingsThroughAContinuation() {
        val doc = open("- [x] one\r\n- [ ] two\r\n".toByteArray())
        val caret = "- [x] one\n- [ ] two".length
        val result = doc.apply(EditCommand.Newline, TextSelection.caret(caret))
        assertArrayEquals("- [x] one\r\n- [ ] two\r\n- [ ] \r\n".toByteArray(), doc.bytes())
        assertEquals(TextSelection.caret(caret + "\n- [ ] ".length), result.selection)
    }

    @Test
    fun checkboxTapChangesOneByteOfACrlfEmojiFile() {
        val original = "# 🎉 Party\r\n\r\n- [ ] 👋🏽 invite\r\n- [ ] cake\r\n".toByteArray()
        val doc = open(original)
        val line = doc.snapshot().toString().indexOf("- [ ] cake")
        doc.apply(EditCommand.ToggleTaskCheckbox(line), TextSelection.caret(0))
        val saved = doc.bytes()
        assertEquals(original.size, saved.size)
        assertEquals(1, original.indices.count { original[it] != saved[it] })
    }

    @Test
    fun undoingACommandIsOneStep() {
        val doc = open("one\r\ntwo\r\n".toByteArray())
        doc.apply(EditCommand.ToggleList(ListKind.Numbered), TextSelection(0, "one\ntwo".length))
        assertEquals("1. one\n2. two\n", doc.snapshot().toString())
        doc.undo()
        assertEquals("one\ntwo\n", doc.snapshot().toString())
    }
}
