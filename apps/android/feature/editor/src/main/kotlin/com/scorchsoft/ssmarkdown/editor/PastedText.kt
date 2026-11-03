package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand
import com.scorchsoft.ssmarkdown.markdown.edit.ExistingLink
import com.scorchsoft.ssmarkdown.markdown.edit.FenceStates
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.markdown.paste.PasteClip
import com.scorchsoft.ssmarkdown.text.PieceTree

/**
 * What a paste in Code puts in the text (docs/ux-spec.md, "Copy and
 * paste"). Code is raw text, so plain text goes in as copied: only what
 * cannot be text is changed here (line endings are the document's own
 * job). HTML goes in as Markdown.
 */
internal object PastedText {
    private const val NUL = '\u0000'
    private const val BOM = '\uFEFF'

    private val webAddress = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)

    /**
     * [text] without NUL (a NUL in the first 64K chars made the saved file
     * reopen read-only as "not text") and without a leading BOM (an
     * encoding mark from the source app, not a character the user saw).
     */
    fun clean(text: CharSequence): CharSequence {
        val start = if (text.isNotEmpty() && text[0] == BOM) 1 else 0
        if (start == 0 && text.indexOf(NUL) < 0) return text
        return text.subSequence(start, text.length).filterNot { it == NUL }
    }

    /**
     * What a paste of a clip with [plain] text and/or [html] inserts
     * (docs/ux-spec.md, "Copy and paste"): with [plainOnly], only the words
     * ([PasteClip.words]); HTML as Markdown ([PasteClip.content]), or its
     * plain text inside a code block ([inCode]); plain text as it is. Runs
     * off the main thread for HTML and large clips.
     */
    fun forClip(
        plain: CharSequence?,
        html: String?,
        plainOnly: Boolean,
        inCode: Boolean,
    ): CharSequence {
        val text = plain?.toString()?.takeIf { it.isNotEmpty() }
        return when {
            plainOnly -> PasteClip.words(text, html)
            html == null -> plain ?: ""
            inCode -> text ?: PasteClip.words(null, html)
            else -> PasteClip.content(text, html).text
        }
    }

    /** True when the caret's line is inside a fenced code block, where HTML pastes as its plain text. */
    fun inCode(view: MarkdownCodeView): Boolean {
        val text = view.session.document.text
        val line = text.positionAt(view.session.selectionStart).line
        return view.fenceStates().roleAt(text.lineStartOffset(line)).isCode
    }

    /**
     * `[selection](address)` when [clip] is one bare http(s) address and
     * `[start, end)` is selected text on one line, outside code (a fence,
     * per [isCodeLine], or a code span), not itself an address and outside an existing link; null
     * otherwise, and the clip pastes as text.
     */
    fun linkFor(
        text: PieceTree,
        start: Int,
        end: Int,
        clip: String,
        isCodeLine: (line: Int) -> Boolean,
    ): String? {
        val url = clip.trim()
        if (start >= end || !webAddress.matches(url)) return null
        val line = text.positionAt(start).line
        val lineStart = text.lineStartOffset(line)
        val selected = text.getText(start, end)
        val inCode =
            isCodeLine(line) ||
                selected.any { it == '\n' || it == '`' } ||
                text.getText(lineStart, start).count { it == '`' } % 2 == 1
        // Never a link of an address to an address (EB-LINK-07), and never a link inside a link.
        if (inCode || ExistingLink.isUrl(selected) ||
            ExistingLink.at(text, TextSelection(start, end)) != null
        ) {
            return null
        }
        // The Link prompt's own writer (brackets escaped, awkward addresses in <>), run on the selection alone.
        return EditCommand
            .SetLink(selected, url)
            .apply(selected, TextSelection(0, selected.length), DocumentStyle.DEFAULT, FenceStates.NONE)
            .applyTo(selected)
    }

    /**
     * Links the selection when [clip] is one bare web address over selected
     * text outside code (EB-LINK-05), as two undo steps (decision D9,
     * EB-UNDO-07): the plain paste, then the link, so the first Undo leaves
     * the address as pasted text and the second brings the selection back.
     * False when the clip pastes as text instead.
     */
    fun pasteLink(
        view: MarkdownCodeView,
        clip: CharSequence,
    ): Boolean {
        val session = view.session
        val text = session.document.text
        val start = session.selectionStart
        val written =
            linkFor(text, start, session.selectionEnd, clip.toString()) { line ->
                view.fenceStates().roleAt(text.lineStartOffset(line)).isCode
            } ?: return false
        val plain = clean(clip)
        view.applyEdit(start, session.selectionEnd, plain)
        view.applyEdit(start, start + plain.length, written)
        return true
    }
}
