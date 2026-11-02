package com.scorchsoft.ssmarkdown.preview.editing

import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.markdown.edit.FormatState
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.DocumentSnapshot
import com.scorchsoft.ssmarkdown.preview.DocumentSource
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PreviewFormatRequest
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit

/** The document a [PreviewEditor] edits. The host owns it; the editor never holds text. */
interface PreviewDocument {
    /** The text now. */
    fun text(): PreviewText

    /** The file's Markdown dialect, detected once (`DocumentStyle.detect`). */
    val style: DocumentStyle

    /**
     * Applies [edits] (sorted, non-overlapping, relative to [text]) as one undo
     * step. This must not call [PreviewEditor.onHostEdited] back: the editor
     * has already told the page.
     */
    fun apply(
        edits: List<TextEdit>,
        origin: EditOrigin,
    )
}

/** Where a change made through [PreviewDocument.apply] came from, for undo grouping. */
enum class EditOrigin {
    /** Typing, deleting, pasting or a task tick in Preview. */
    PREVIEW_TYPING,

    /** A strip key or block style applied to Preview's selection. */
    PREVIEW_FORMAT,
}

/**
 * Everything editing in Preview needs on the Kotlin side, in one place:
 *
 * - opening the page ([open], and [documentSource] for renderer recovery),
 * - the host's own changes ([onHostEdited]) going to the page as line edits
 *   that the page can rebase its typing over,
 * - the page's edits ([onPageEdits]) rebased over host changes it had not
 *   seen, applied, and acknowledged; a reload only when rebasing is
 *   impossible ([onResync]),
 * - strip keys ([format]) applied with core:markdown's EditCommand on the
 *   source range the page's source map gives for the selection
 *   ([onFormatRequest]), sent back as minimal edits with the selection restored.
 *
 * Wire a [com.scorchsoft.ssmarkdown.preview.PreviewListener]'s editing methods
 * to the matching methods here, and [send] to `PreviewView.send`.
 */
class PreviewEditor(
    private val document: PreviewDocument,
    private val send: (HostMessage) -> Unit,
    /**
     * This binding's number, sent with every load and stamped by the page
     * on what it sends back: a host keeps one editor per document and routes
     * each page edit to the editor of the epoch it was typed in (DS-13).
     */
    val epoch: Int = 0,
) {
    private val sync = PreviewEditSync()

    /** The revision the page and host agree on next. */
    val rev: Int get() = sync.rev

    /** Hands a renderer restart a fresh load at a new revision (`PreviewView.documentSource`). */
    val documentSource: DocumentSource =
        DocumentSource {
            val load = sync.load(document.text().chars, epoch = epoch)
            DocumentSnapshot(load.text, load.rev, epoch)
        }

    /**
     * Loads the document into the page, landing at [anchorLine]. The text
     * goes as the document's own characters (an immutable snapshot), so
     * nothing here copies the whole document; `PreviewChannel` frames it
     * off the main thread (PERF-12).
     */
    fun open(anchorLine: Int? = null) {
        send(sync.load(document.text().chars, anchorLine, epoch))
    }

    /** The host changed its text (Code pane, undo, replace): [edits] were applied to [before]. */
    fun onHostEdited(
        before: PreviewText,
        edits: List<TextEdit>,
    ) {
        sync.onHostEdits(before, edits)?.let(send)
    }

    /** `PreviewListener.onSourceEdits`: rebase, apply, acknowledge; reload only if rebasing is impossible. */
    fun onPageEdits(
        baseRev: Int,
        edits: List<TextEdit>,
    ) {
        when (val outcome = sync.onPageEdits(baseRev, edits, document.text().length)) {
            is PageEditOutcome.Apply -> {
                if (outcome.edits.isNotEmpty()) document.apply(outcome.edits, EditOrigin.PREVIEW_TYPING)
                send(outcome.ack)
            }

            is PageEditOutcome.Resync -> {
                open()
            }
        }
    }

    /** `PreviewListener.onResync`: the page lost step; its edits already arrived, so reload it. */
    @Suppress("UNUSED_PARAMETER")
    fun onResync(reason: String) {
        open()
    }

    /** A strip key or block style in Preview: asks the page for its selection, which comes back as a request. */
    fun format(action: FormatAction) {
        send(HostMessage.Format(action))
    }

    /**
     * `PreviewListener.onFormatRequest`. Returns false for what is not a
     * format (undo, redo), which the host handles itself.
     */
    fun onFormatRequest(request: PreviewFormatRequest): Boolean {
        if (request.action.isHistory) return false
        val before = document.text()
        val selection = mappedSelection(request.from, request.to, request.baseRev, before.length)
        val result =
            selection?.let {
                PreviewFormatter.format(before.chars, it, request.action, document.style, request.inTable)
            }
        if (result != null) {
            if (result.edits.isNotEmpty()) {
                document.apply(result.edits, EditOrigin.PREVIEW_FORMAT)
                sync.onHostEdits(before, result.edits)?.let(send)
            }
            send(HostMessage.Select(sync.rev, result.selection.start, result.selection.end))
        }
        return true
    }

    /** What the strip should show pressed for the page's selection, or null if it is stale. */
    fun formatState(
        from: Int,
        to: Int,
        baseRev: Int,
    ): FormatState? {
        val text = document.text()
        return mappedSelection(from, to, baseRev, text.length)?.let { FormatState.at(text.chars, it) }
    }

    /**
     * [request]'s selection on the host's text now, or null if it is stale:
     * for an action the host answers itself, such as the Link prompt, which
     * asks where the link goes before anything is written.
     */
    fun selectionOf(request: PreviewFormatRequest): TextSelection? =
        mappedSelection(request.from, request.to, request.baseRev, document.text().length)

    /** The page's selection on the host's text now, or null if it is stale or out of range. */
    private fun mappedSelection(
        from: Int,
        to: Int,
        baseRev: Int,
        length: Int,
    ): TextSelection? {
        val a = sync.mapFromPage(from, baseRev)
        val b = sync.mapFromPage(to, baseRev)
        if (a == null || b == null) return null
        return TextSelection(minOf(a, b), maxOf(a, b)).takeIf { it.start >= 0 && it.end <= length }
    }
}
