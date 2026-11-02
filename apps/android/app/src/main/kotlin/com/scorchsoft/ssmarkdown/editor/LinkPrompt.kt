package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.document.fencesFor
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand
import com.scorchsoft.ssmarkdown.markdown.edit.ExistingLink
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection

/**
 * What the Link prompt opens with (docs/ux-spec.md, "Formatting strip").
 * [editing] is true inside an existing link, which the prompt rewrites (or
 * removes) rather than wrapping the selection.
 */
data class LinkFields(
    val text: String,
    val url: String,
    val editing: Boolean = false,
)

/**
 * The strip's Link key (docs/ux-spec.md, "Formatting strip"): one tap
 * opens a prompt for the link's text and address instead of writing an
 * empty `[text]()`. Code opens it on the editor's selection; Preview on the
 * page's, mapped onto the host's text (`PreviewSession`). Done and Remove
 * link run `core:markdown`'s commands on that selection through the
 * editor surface, so the edit is one undo step and reaches the page like
 * any other host edit; Cancel changes nothing.
 */
class LinkController(
    private val host: EditorHost,
) {
    /** Opens the prompt for [selection], prefilled from the text there. */
    fun open(selection: TextSelection) {
        val doc = host.active ?: return
        doc.surface.flushComposing()
        val text = doc.text.snapshot()
        if (selection.end > text.length) return
        val selected = text.subSequence(selection.start, selection.end).toString()
        val fields = LinkPrompt.fields(selected, ExistingLink.at(text, selection))
        host.update { it.copy(overlay = EditorOverlay.LinkPrompt(fields, selection)) }
    }

    /** Done: writes `[text](url)` over the prompt's selection, or rewrites the link it is in. */
    fun onDone(
        prompt: EditorOverlay.LinkPrompt,
        text: String,
        url: String,
    ) {
        if (url.isBlank()) return
        run(EditCommand.SetLink(text, url), prompt.selection)
    }

    /** Remove link: the link becomes its text. */
    fun onRemove(prompt: EditorOverlay.LinkPrompt) = run(EditCommand.RemoveLink, prompt.selection)

    private fun run(
        command: EditCommand,
        selection: TextSelection,
    ) {
        val doc = host.active ?: return
        val surface = doc.surface
        surface.flushComposing()
        val snapshot = doc.text.snapshot()
        // The text cannot change under a modal prompt, but a stale range must never write out of bounds.
        if (selection.end > snapshot.length) return
        val result = command.apply(snapshot, selection, doc.style, surface.fencesFor(snapshot))
        surface.apply(result.ops, result.selection)
        host.refresh()
    }
}

/** The prompt's prefill rules, kept pure so every path is tested without a UI. */
object LinkPrompt {
    /**
     * Inside a link: its text and address. Otherwise the selected text, and
     * as the address the selection itself when it is one.
     */
    fun fields(
        selected: String,
        existing: ExistingLink?,
    ): LinkFields =
        when {
            existing != null -> LinkFields(existing.label, existing.url, editing = true)
            ExistingLink.isUrl(selected) -> LinkFields(selected.trim(), selected.trim())
            else -> LinkFields(selected, "")
        }

    /** An empty address takes the clipboard's, when the clipboard holds one. */
    fun withClipboard(
        fields: LinkFields,
        clipboard: String?,
    ): LinkFields =
        if (fields.url.isBlank() && clipboard != null && ExistingLink.isUrl(clipboard)) {
            fields.copy(url = clipboard.trim())
        } else {
            fields
        }
}
