package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.EditorListener
import com.scorchsoft.ssmarkdown.editor.LineChange
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.Snapshot
import com.scorchsoft.ssmarkdown.text.TextDocument

/**
 * What the editor screen hears from the document on screen. The view
 * model implements it; every call is on the main thread.
 */
interface ActiveDocumentListener {
    /** The text changed by typing, a command or undo. */
    fun onEdited() = Unit

    /** The selection settled. */
    fun onSelectionChanged(
        start: Int,
        end: Int,
    ) = Unit

    /** The source line at the top of the Code pane changed. */
    fun onAnchorLineChanged(line: Int) = Unit

    /** The Code pane's zoom changed; [pinching] while fingers are down. */
    fun onZoomChanged(
        percent: Int,
        pinching: Boolean,
    ) = Unit

    /** A copy was refused for Binder size; offer Share as file. */
    fun onCopyTooLarge(chars: Int) = Unit

    /** A hardware shortcut the editor does not own (Ctrl+S, Ctrl+F, Ctrl+K…). */
    fun onShortcut(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
    ): Boolean = false
}

/**
 * Hears every change to the buffer with the text it was made on: what the
 * Preview needs to send the page minimal line edits it can rebase over.
 */
fun interface TextChangeListener {
    /** [ops] were applied one after another to [before]. */
    fun onTextChanged(
        before: Snapshot,
        ops: List<EditOp>,
    )
}

/**
 * The document on screen, bound to its view: the bridge between
 * `MarkdownCodeView` (which edits the [TextDocument] directly) and the
 * store's [OpenDocument] (which must journal every edit before anything
 * else happens — the never-lose-work promise at the keystroke level).
 *
 * One instance per [TextDocument]: when the store replaces the buffer
 * (a reload or a resolved conflict) the session builds a new one, so the
 * old undo stack and snapshots never reach into new content.
 */
@Suppress("TooManyFunctions") // The EditorListener contract plus the journal and change bridges.
class ActiveDocument(
    val open: OpenDocument,
    private val listener: ActiveDocumentListener,
) : EditorListener {
    /** The buffer this session is bound to. */
    val text: TextDocument = open.document

    /** Headings for the Navigator, breadcrumb and Copy section. */
    val outline: OutlineTracker = OutlineTracker(text.text)

    /** The file's own Markdown dialect, detected once (ux-spec "Editing in Preview"). */
    val style: DocumentStyle = DocumentStyle.detect(text.snapshot())

    private val recorder = EditRecorder(text.snapshot())
    private var view: MarkdownCodeView? = null
    private val buffer = BufferSurface(open) { before, ops -> afterBufferEdit(before, ops) }

    /** True once the journal is known to be running for the current run of unsaved edits. */
    private var journalPrimed = false

    /** Hears every change, from any surface; the Preview session sets it. */
    var changes: TextChangeListener? = null

    /** Where programmatic edits go: the Code view while it is attached, else the buffer. */
    val surface: EditorSurface get() = view?.let(::CodeViewSurface) ?: buffer

    /** The Code view is on screen (or moved by `movableContentOf`; it is the same instance). */
    fun attach(codeView: MarkdownCodeView) {
        view = codeView
    }

    /** The Code view left the composition. */
    fun detach(codeView: MarkdownCodeView) {
        if (view === codeView) view = null
    }

    override fun onTextEdited(
        change: LineChange,
        version: Long,
    ) {
        val before = recorder.snapshot
        val op = recorder.record(text.text, change.firstLine, change.removedBreaks, change.addedBreaks)
        startJournalBefore(op)
        open.recordApplied(listOf(op))
        outline.onLinesChanged(change.firstLine, change.removedBreaks, change.addedBreaks)
        if (!op.isNoOp) changes?.onTextChanged(before, listOf(op))
        listener.onEdited()
    }

    /**
     * The document is clean again (saved, discarded or reloaded): the next
     * edit starts a new run, so the journal may need starting before it.
     */
    fun onClean() {
        journalPrimed = false
    }

    /**
     * Starts the journal on the text **before** [op] (docs/robustness.md §1).
     *
     * The Code view applies keystrokes to the buffer itself and only then
     * reports them, but the journal starts lazily and, when an earlier
     * session's journal had to be set aside, it snapshots "the text before
     * the coming edit". Snapshotting after the view's edit and then
     * appending that edit would replay it twice after a crash. So the raw
     * text steps back over [op] while the journal starts (no undo record,
     * no version change: the view never sees it) and forward again after.
     * This runs once per run of unsaved edits, not per keystroke.
     */
    private fun startJournalBefore(op: EditOp) {
        if (journalPrimed || op.isNoOp) return
        val tree = text.text
        op.inverse().applyTo(tree)
        try {
            open.prepareForEdit()
        } finally {
            op.applyTo(tree)
        }
        journalPrimed = true
    }

    override fun onSelectionChanged(
        start: Int,
        end: Int,
    ) = listener.onSelectionChanged(start, end)

    override fun onAnchorLineChanged(line: Int) = listener.onAnchorLineChanged(line)

    override fun onZoomChanged(
        percent: Int,
        pinching: Boolean,
    ) = listener.onZoomChanged(percent, pinching)

    override fun onCopyTooLarge(chars: Int) = listener.onCopyTooLarge(chars)

    override fun onShortcut(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
    ): Boolean = listener.onShortcut(keyCode, ctrl, shift)

    private fun afterBufferEdit(
        before: Snapshot,
        ops: List<EditOp>,
    ) {
        // Buffer edits are journalled by OpenDocument itself (it starts the journal before applying).
        journalPrimed = true
        recorder.reset(text.snapshot())
        outline.invalidateAll()
        if (ops.isNotEmpty()) changes?.onTextChanged(before, ops)
        listener.onEdited()
    }
}
