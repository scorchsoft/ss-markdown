package com.scorchsoft.ssmarkdown.preview.editing

import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.markdown.edit.InlineFormat
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PreviewFormatRequest
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewEditorTest {
    private class Doc(
        var text: String,
    ) : PreviewDocument {
        val applied = mutableListOf<Pair<List<TextEdit>, EditOrigin>>()

        override fun text(): PreviewText = PreviewText.of(text)

        override val style: DocumentStyle get() = DocumentStyle.detect(text)

        override fun apply(
            edits: List<TextEdit>,
            origin: EditOrigin,
        ) {
            applied += edits to origin
            text = TextTransform.apply(text, edits)
        }
    }

    private fun editor(text: String): Triple<Doc, PreviewEditor, MutableList<HostMessage>> {
        val doc = Doc(text)
        val sent = mutableListOf<HostMessage>()
        val editor = PreviewEditor(doc, sent::add)
        editor.open()
        return Triple(doc, editor, sent)
    }

    @Test
    fun `bold from the strip wraps the source range in the file's own marker and restores the selection`() {
        val (doc, editor, sent) = editor("Some __strong__ text. Make this bold.")
        val from = doc.text.indexOf("this")
        sent.clear()
        editor.format(FormatAction.BOLD)
        assertEquals(listOf(HostMessage.Format(FormatAction.BOLD)), sent)
        sent.clear()
        assertTrue(editor.onFormatRequest(PreviewFormatRequest(FormatAction.BOLD, from, from + 4, editor.rev, false)))
        assertEquals("Some __strong__ text. Make __this__ bold.", doc.text)
        assertEquals(listOf(TextEdit(from, from, "__"), TextEdit(from + 4, from + 4, "__")), doc.applied.single().first)
        assertEquals(EditOrigin.PREVIEW_FORMAT, doc.applied.single().second)
        val edit = sent[0] as HostMessage.Edit
        assertEquals(doc.applied.single().first, edit.ops)
        assertEquals(HostMessage.Select(edit.rev, from + 2, from + 6), sent[1])
    }

    @Test
    fun `block styles change only the touched line`() {
        val (doc, editor, _) = editor("Intro\n\nA heading\n\nOutro")
        val at = doc.text.indexOf("heading")
        editor.onFormatRequest(PreviewFormatRequest(FormatAction.HEADING_2, at, at, editor.rev, false))
        assertEquals("Intro\n\n## A heading\n\nOutro", doc.text)
        editor.onFormatRequest(PreviewFormatRequest(FormatAction.PARAGRAPH, at + 3, at + 3, editor.rev, false))
        assertEquals("Intro\n\nA heading\n\nOutro", doc.text)
        editor.onFormatRequest(PreviewFormatRequest(FormatAction.QUOTE, at, at, editor.rev, false))
        assertEquals("Intro\n\n> A heading\n\nOutro", doc.text)
    }

    @Test
    fun `a selection the page took before a Code edit is moved over it`() {
        val (doc, editor, _) = editor("alpha beta")
        val pageRev = editor.rev
        val before = doc.text()
        doc.text = "NEW alpha beta"
        editor.onHostEdited(before, listOf(TextEdit(0, 0, "NEW ")))
        editor.onFormatRequest(PreviewFormatRequest(FormatAction.ITALIC, 6, 10, pageRev, false))
        assertEquals("NEW alpha *beta*", doc.text)
    }

    @Test
    fun `line formats are refused in a table cell and history is left to the host`() {
        val (doc, editor, sent) = editor("|a|b|\n|-|-|\n|1|2|")
        sent.clear()
        editor.onFormatRequest(PreviewFormatRequest(FormatAction.HEADING_1, 13, 13, editor.rev, true))
        assertEquals("|a|b|\n|-|-|\n|1|2|", doc.text)
        assertFalse(editor.onFormatRequest(PreviewFormatRequest(FormatAction.UNDO, 0, 0, editor.rev, false)))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `page edits are rebased, applied as typing and acknowledged`() {
        val (doc, editor, sent) = editor("hello")
        val pageRev = editor.rev
        val before = doc.text()
        doc.text = "# hello"
        editor.onHostEdited(before, listOf(TextEdit(0, 0, "# ")))
        sent.clear()
        editor.onPageEdits(pageRev, listOf(TextEdit(5, 5, "!")))
        assertEquals("# hello!", doc.text)
        assertEquals(EditOrigin.PREVIEW_TYPING, doc.applied.last().second)
        assertEquals(listOf(HostMessage.Ack(editor.rev)), sent)
    }

    @Test
    fun `an impossible rebase reloads the page from the host's text`() {
        val (_, editor, sent) = editor("hello")
        sent.clear()
        editor.onPageEdits(editor.rev + 5, listOf(TextEdit(0, 0, "x")))
        assertEquals(HostMessage.Load(editor.rev, "hello"), sent.single())
    }

    @Test
    fun `the strip's pressed state comes from core markdown at the mapped selection`() {
        val (_, editor, _) = editor("a **bold** word")
        assertEquals(setOf(InlineFormat.Bold), editor.formatState(5, 7, editor.rev)?.inline)
        assertNull(editor.formatState(5, 7, editor.rev + 3))
    }

    @Test
    fun `the formatter keeps edits minimal`() {
        val r =
            PreviewFormatter.format("- a\n- b", TextSelection(2, 2), FormatAction.NUMBERED, DocumentStyle.DEFAULT)
                ?: error("no result")
        assertEquals("1. a\n- b", TextTransform.apply("- a\n- b", r.edits))
    }
}
