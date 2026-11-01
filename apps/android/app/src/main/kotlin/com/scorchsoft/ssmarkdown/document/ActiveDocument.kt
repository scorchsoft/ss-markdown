package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.EditorListener
import com.scorchsoft.ssmarkdown.editor.LineChange
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.clearFindMatches
import com.scorchsoft.ssmarkdown.editor.setFindMatches
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.text.EditObserver
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
 * store's [OpenDocument].
 *
 * Journalling is not done here. The [OpenDocument] hears every op the
 * [TextDocument] applies, from any surface, and journals it before it is
 * applied (docs/robustness.md §1). This class only listens to the same ops
 * so the Preview gets exact char edits, never whole lines (PERF-5), and
 * tells the store when a run of view edits has landed.
 *
 * One instance per [TextDocument]: when the store replaces the buffer
 * (a reload or a resolved conflict) the session builds a new one, so the
 * old undo stack and snapshots never reach into new content.
 */
@Suppress("TooManyFunctions") // The EditorListener contract plus the change bridges.
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

    private var view: MarkdownCodeView? = null
    private val buffer = BufferSurface(open) { before, ops -> afterBufferEdit(before, ops) }

    /** The text before the ops in [applied]; taken lazily, only while someone listens for changes. */
    private var before: Snapshot? = null
    private val applied = ArrayList<EditOp>(1)
    private val opTap =
        object : EditObserver {
            override fun beforeEdit(op: EditOp) {
                if (changes == null) return
                if (before == null) before = text.snapshot()
                applied += op
            }
        }

    /** Hears every change, from any surface; the Preview session sets it. */
    var changes: TextChangeListener? = null

    init {
        text.addEditObserver(opTap)
    }

    /** Where programmatic edits go: the Code view while it is attached, else the buffer. */
    val surface: EditorSurface get() = view?.let(::CodeViewSurface) ?: buffer

    /** The Code view is on screen (or moved by `movableContentOf`; it is the same instance). */
    fun attach(codeView: MarkdownCodeView) {
        view = codeView
    }

    /** Highlights find matches in the Code view, if it is on screen; [current] is an index or -1. */
    fun highlight(
        starts: IntArray,
        ends: IntArray,
        current: Int,
    ) {
        view?.setFindMatches(starts, ends, current)
    }

    /** Removes the find highlights: the bar closed. */
    fun clearHighlights() {
        view?.clearFindMatches()
    }

    /** The Code view left the composition. */
    fun detach(codeView: MarkdownCodeView) {
        if (view === codeView) view = null
    }

    /** This session is no longer on screen: it stops listening to the buffer. */
    fun release() {
        text.removeEditObserver(opTap)
        changes = null
        before = null
        applied.clear()
    }

    override fun onTextEdited(
        change: LineChange,
        version: Long,
    ) {
        open.onEditsApplied()
        outline.onLinesChanged(change.firstLine, change.removedBreaks, change.addedBreaks)
        dispatchChanges()
        listener.onEdited()
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
        outline.invalidateAll()
        // The buffer surface reports its own "before"; the ops it applied were also tapped.
        this.before = null
        applied.clear()
        if (ops.isNotEmpty()) changes?.onTextChanged(before, ops)
        listener.onEdited()
    }

    /** Hands the ops applied since the last report, with the text they were applied to, to [changes]. */
    private fun dispatchChanges() {
        val start = before
        before = null
        if (applied.isEmpty() || start == null) return
        val ops = applied.toList()
        applied.clear()
        changes?.onTextChanged(start, ops)
    }
}
