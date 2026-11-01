package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import com.scorchsoft.ssmarkdown.preview.editing.EditOrigin
import com.scorchsoft.ssmarkdown.preview.editing.PreviewDocument
import com.scorchsoft.ssmarkdown.preview.editing.PreviewText
import com.scorchsoft.ssmarkdown.text.EditOp

/**
 * The document on screen as `PreviewEditor` edits it. Edits from the page
 * go through the same [ActiveDocument.surface] as the strip's: through the
 * Code view when it is on screen (Split), so its layout and keyboard see
 * them, else straight into the journalled buffer. Either way they are one
 * undo step and are journalled before anything else happens.
 *
 * [applying] brackets each apply, so the session does not echo the page's
 * own edit back to it as a host change: `PreviewEditor` acks it instead.
 */
internal class ActivePreviewDocument(
    private val document: ActiveDocument,
    private val applying: (Boolean) -> Unit,
) : PreviewDocument {
    override val style: DocumentStyle get() = document.style

    override fun text(): PreviewText = PreviewText.of(document.text.snapshot())

    override fun apply(
        edits: List<TextEdit>,
        origin: EditOrigin,
    ) {
        if (edits.isEmpty()) return
        val snapshot = document.text.snapshot()
        val ops =
            edits
                .sortedByDescending { it.from }
                .map { EditOp(it.from, snapshot.subSequence(it.from, it.to), it.insert) }
        applying(true)
        try {
            document.surface.apply(ops, TextSelection.caret(caretAfter(edits)))
        } finally {
            applying(false)
        }
    }

    /** Just after the last edit, in the text after all of them: where the page's caret is. */
    private fun caretAfter(edits: List<TextEdit>): Int {
        val sorted = edits.sortedBy { it.from }
        val last = sorted.last()
        val shift = sorted.dropLast(1).sumOf { it.insert.length - (it.to - it.from) }
        return last.from + shift + last.insert.length
    }
}
