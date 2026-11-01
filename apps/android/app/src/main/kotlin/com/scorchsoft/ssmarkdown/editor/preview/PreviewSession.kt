package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.document.TextChangeListener
import com.scorchsoft.ssmarkdown.preview.DocumentSnapshot
import com.scorchsoft.ssmarkdown.preview.DocumentSource
import com.scorchsoft.ssmarkdown.preview.PreviewListener
import com.scorchsoft.ssmarkdown.preview.PreviewView
import com.scorchsoft.ssmarkdown.preview.RendererState
import com.scorchsoft.ssmarkdown.preview.bridge.Crumb
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.LinkTarget
import com.scorchsoft.ssmarkdown.preview.bridge.MediaKind
import com.scorchsoft.ssmarkdown.preview.bridge.PreviewFormatRequest
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import com.scorchsoft.ssmarkdown.preview.editing.PreviewEditor
import com.scorchsoft.ssmarkdown.preview.editing.PreviewText
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.Snapshot

/**
 * The editor's side of the Preview: binds `PreviewEditor` to the document
 * on screen and the [PreviewView] to both.
 *
 * Process-scoped like the editor, while the view is the Activity's: the
 * view [attach]es when the Preview pane is first shown and [detach]es when
 * the Activity goes. With no view, nothing is sent; attaching loads the
 * page fresh at the anchor, so it can never fall out of step.
 *
 * Text only ever reaches the page through `PreviewEditor` (feature/preview
 * CLAUDE.md): host changes as line edits it can rebase over ([onHostChanged]),
 * page edits rebased, applied and acked, never applied directly.
 */
@Suppress("TooManyFunctions") // The PreviewListener contract plus the few commands the editor sends.
class PreviewSession(
    private val host: PreviewHost,
) : PreviewListener {
    /** Rendered diagrams for the viewer, the Mermaid sheet and Export images. */
    val exports = DiagramExports(::send, onFinished = { scrollTo(host.anchorLine) })

    /** The view on screen, if any. */
    var view: PreviewView? = null
        private set

    private var sender: ((HostMessage) -> Unit)? = null
    private var editor: PreviewEditor? = null
    private var bound: ActiveDocument? = null
    private var applyingPageEdit = false

    /** The revision the page and host agree on next; for tests and logs. */
    val rev: Int get() = editor?.rev ?: 0

    /** [document] is now on screen (null: nothing is). Reloads the page if one is attached. */
    fun bind(document: ActiveDocument?) {
        if (document === bound) return
        bound?.changes = null
        bound = document
        editor = document?.let { PreviewEditor(ActivePreviewDocument(it) { on -> applyingPageEdit = on }, ::send) }
        document?.changes = TextChangeListener(::onHostChanged)
        open()
    }

    /** The Preview pane's view is on screen: it reports here and loads the document. */
    fun attach(target: PreviewView) {
        if (view === target) return
        view = target
        sender = target::send
        target.listener = this
        target.documentSource =
            DocumentSource { editor?.documentSource?.snapshot() ?: DocumentSnapshot("", 0) }
        open()
    }

    /** The Activity that owned [target] is going. */
    fun detach(target: PreviewView) {
        if (view !== target) return
        target.listener = null
        target.documentSource = null
        view = null
        sender = null
    }

    /**
     * Connects a page that is not a [PreviewView]: the tests' recording
     * page, which hears exactly what a WebView would.
     */
    internal fun attachPage(send: (HostMessage) -> Unit) {
        sender = send
        open()
    }

    /** Scrolls the page so [line] is at the top. */
    fun scrollTo(line: Int) = send(HostMessage.ScrollToLine(line))

    /** A strip key in Preview. */
    fun format(action: FormatAction) {
        editor?.format(action)
    }

    /** The strip's Done key: back to reading. */
    fun endEditing() = send(HostMessage.EndEditing)

    /** Sends [message] to the page, if one is attached. */
    fun send(message: HostMessage) {
        sender?.invoke(message)
    }

    private fun open() {
        if (sender == null) return
        editor?.open(host.anchorLine)
    }

    /** A change from the Code pane, the strip, undo or replace: the page hears it as line edits. */
    private fun onHostChanged(
        before: Snapshot,
        ops: List<EditOp>,
    ) {
        val target = editor
        val doc = bound
        val listening = !applyingPageEdit && sender != null
        if (target == null || doc == null || !listening) return
        val single = ops.singleOrNull()
        val edit =
            if (single != null) {
                TextEdit(single.offset, single.offset + single.deleted.length, single.inserted.toString())
            } else {
                CoveringEdit.of(before.length, ops, doc.text.snapshot())
            }
        edit?.let { target.onHostEdited(PreviewText.of(before), listOf(it)) }
    }

    override fun onTopLine(
        line: Int,
        crumbs: List<Crumb>,
    ) = host.onPreviewTopLine(line)

    override fun onRendererStateChanged(state: RendererState) = host.onPreviewEvent(PreviewEvent.Renderer(state))

    override fun onPageError(message: String) = exports.onError()

    override fun onSourceEdits(
        baseRev: Int,
        edits: List<TextEdit>,
    ) {
        editor?.onPageEdits(baseRev, edits)
    }

    override fun onEditingChanged(editing: Boolean) = host.onPreviewEvent(PreviewEvent.Editing(editing))

    override fun onSelection(
        from: Int,
        to: Int,
        marks: Set<String>,
        inTable: Boolean,
        baseRev: Int,
    ) {
        editor?.formatState(from, to, baseRev)?.let { host.onPreviewEvent(PreviewEvent.Format(it)) }
    }

    override fun onFormatRequest(request: PreviewFormatRequest) {
        val handled = editor?.onFormatRequest(request) ?: return
        if (!handled) host.onPreviewEvent(PreviewEvent.History(redo = request.action == FormatAction.REDO))
    }

    override fun onResync(reason: String) {
        editor?.onResync(reason)
    }

    override fun onEditRefused(line: Int) = host.onPreviewEvent(PreviewEvent.EditRefused(line))

    override fun onTableMenu(
        line: Int,
        row: Int,
        column: Int,
    ) = host.onPreviewEvent(PreviewEvent.TableMenu(line))

    override fun onLinkTapped(
        target: LinkTarget,
        line: Int,
    ) = host.onPreviewEvent(PreviewEvent.LinkTapped(target, line))

    override fun onHeadingLongPress(
        line: Int,
        level: Int,
        text: String,
    ) = host.onPreviewEvent(PreviewEvent.HeadingLongPress(line))

    override fun onEditInCode(line: Int) = host.onPreviewEvent(PreviewEvent.EditInCode(line))

    override fun onOpenViewer(
        kind: MediaKind,
        index: Int,
        line: Int,
    ) = host.onPreviewEvent(PreviewEvent.OpenViewer(kind, line))

    override fun onMediaLongPress(
        kind: MediaKind,
        index: Int,
        line: Int,
    ) = host.onPreviewEvent(PreviewEvent.MediaLongPress(kind, line))

    override fun onExportResult(
        requestId: Int,
        mime: String,
        bytes: ByteArray,
    ) = exports.onResult(requestId, bytes)
}
