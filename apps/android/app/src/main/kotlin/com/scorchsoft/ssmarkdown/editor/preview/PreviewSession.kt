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
import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage
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

    /**
     * The editor of the previous binding, kept so a page edit typed into that
     * document just before a switch, which arrives stamped with its epoch,
     * still lands in that document instead of the new one (DS-13). It sends
     * nothing: the page has moved on.
     */
    private var retired: PreviewEditor? = null
    private var nextEpoch = 1
    private var bound: ActiveDocument? = null
    private var applyingPageEdit = false

    /** Between `onPause` and `onResume`: a page edit arriving now is made durable at once (DS-14). */
    private var paused = false

    /** The revision the page and host agree on next; for tests and logs. */
    val rev: Int get() = editor?.rev ?: 0

    /** [document] is now on screen (null: nothing is). Reloads the page if one is attached. */
    fun bind(document: ActiveDocument?) {
        if (document === bound) return
        // Typing still waiting in the page goes out first, stamped with the old epoch (DS-13).
        send(HostMessage.Flush)
        bound?.changes = null
        bound = document
        retired = editor
        editor = document?.let(::editorFor)
        document?.changes = TextChangeListener(::onHostChanged)
        open()
    }

    /** A new binding's editor: its messages reach the page only while it is the current one. */
    private fun editorFor(document: ActiveDocument): PreviewEditor {
        lateinit var created: PreviewEditor
        val target = ActivePreviewDocument(document) { on -> applyingPageEdit = on }
        created = PreviewEditor(target, { message -> if (editor === created) send(message) }, nextEpoch++)
        return created
    }

    /** The editor a page message stamped with [epoch] belongs to; unstamped means the current one. */
    private fun editorOf(epoch: Int): PreviewEditor? =
        when (epoch) {
            PageMessage.UNSTAMPED, editor?.epoch -> editor
            retired?.epoch -> retired
            else -> null
        }

    /** The app is going to the background: the page writes back any typing still waiting (DS-14). */
    fun onPause() {
        paused = true
        send(HostMessage.Flush)
    }

    /** The app is back. */
    fun onResume() {
        paused = false
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
        epoch: Int,
    ) {
        val target = editorOf(epoch) ?: return
        target.onPageEdits(baseRev, edits)
        // The pause already flushed the journal; this edit arrived after it.
        if (paused && target === editor) host.onPreviewEvent(PreviewEvent.EditedWhilePaused)
    }

    override fun onEditingChanged(editing: Boolean) = host.onPreviewEvent(PreviewEvent.Editing(editing))

    override fun onSelection(
        from: Int,
        to: Int,
        marks: Set<String>,
        inTable: Boolean,
        baseRev: Int,
    ) {
        editor?.formatState(from, to, baseRev)?.let { host.onPreviewEvent(PreviewEvent.Format(it, inTable)) }
    }

    override fun onFormatRequest(request: PreviewFormatRequest) {
        if (request.epoch != PageMessage.UNSTAMPED && request.epoch != editor?.epoch) return
        val handled = editor?.onFormatRequest(request) ?: return
        if (!handled) host.onPreviewEvent(PreviewEvent.History(redo = request.action == FormatAction.REDO))
    }

    override fun onResync(
        reason: String,
        epoch: Int,
    ) {
        // A resync from the previous document's page needs nothing: the page already has a new load.
        if (epoch == PageMessage.UNSTAMPED || epoch == editor?.epoch) editor?.onResync(reason)
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
