package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.EditorListener
import com.scorchsoft.ssmarkdown.editor.LineChange
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.PasteProgress
import com.scorchsoft.ssmarkdown.editor.clearFindMatches
import com.scorchsoft.ssmarkdown.editor.setFindMatches
import com.scorchsoft.ssmarkdown.editor.trimLayoutCaches
import com.scorchsoft.ssmarkdown.editor.trimTokenCache
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.files.memory.ShedStep
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

    /** The Code pane's scroll position over its scrollable range, 0 to 1. */
    fun onScrollFractionChanged(fraction: Float) = Unit

    /** The Code pane's zoom changed; [pinching] while fingers are down. */
    fun onZoomChanged(
        percent: Int,
        pinching: Boolean,
    ) = Unit

    /** A copy was refused for Binder size; offer Share as file. */
    fun onCopyTooLarge(chars: Int) = Unit

    /** May a large paste of [chars] go in? False refuses it; the listener says why. */
    fun admitPaste(chars: Int): Boolean = true

    /** A large paste moved on: the moment to poll memory pressure. */
    fun onPasteProgress(progress: PasteProgress) = Unit

    /** [document]'s outline caught up with its text (it is tokenised in the background). */
    fun onOutlineChanged(document: ActiveDocument) = Unit

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
    threads: DocumentThreads,
) : EditorListener {
    /** The buffer this session is bound to. */
    val text: TextDocument = open.document

    /** Headings for the Navigator, breadcrumb and Copy section. */
    val outline: OutlineTracker = OutlineTracker(text, threads) { listener.onOutlineChanged(this) }

    /** The file's own Markdown dialect, detected once (ux-spec "Editing in Preview"). */
    val style: DocumentStyle = DocumentStyle.detect(text.snapshot())

    /** Re-packs the text when editing has fragmented it (PERF-F). */
    val compactor: TreeCompactor = TreeCompactor(text.text, threads)

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
        outline.start()
    }

    /** Where programmatic edits go: the Code view while it is attached, else the buffer. */
    val surface: EditorSurface get() = view?.let(::CodeViewSurface) ?: buffer

    /** The find highlights last asked for, so a new Code view draws them too; null when none. */
    private var matches: FindHighlights? = null

    /**
     * The Code view is on screen (or moved by `movableContentOf`; it is the
     * same instance). A *new* view (a recreated Activity, or the Code pane
     * coming back after Preview) takes the selection and find highlights
     * the document kept while no view was attached: the view must not
     * decide where the cursor is (robustness §5a, rule 1).
     */
    fun attach(codeView: MarkdownCodeView) {
        if (view === codeView) return
        view?.let { keepSelectionOf(it) }
        view = codeView
        // A view still bound to another document's text (a file switch in progress) gets nothing of ours.
        if (codeView.session.document !== text) return
        val kept = buffer.selection
        codeView.session.setSelection(kept.start, kept.end)
        matches?.let { codeView.setFindMatches(it.starts, it.ends, it.current) }
    }

    /** Highlights find matches in the Code view, if it is on screen; [current] is an index or -1. */
    fun highlight(
        starts: IntArray,
        ends: IntArray,
        current: Int,
    ) {
        matches = FindHighlights(starts, ends, current)
        view?.setFindMatches(starts, ends, current)
    }

    /** Removes the find highlights: the bar closed. */
    fun clearHighlights() {
        matches = null
        view?.clearFindMatches()
    }

    private fun keepSelectionOf(codeView: MarkdownCodeView) {
        buffer.select(codeView.session.selectionStart, codeView.session.selectionEnd)
    }

    /**
     * Gives memory back for one rung of the governor's ladder: the Code
     * view's layouts and display lists, or its cached tokens. Never text,
     * never undo, never block states (performance review PERF-9).
     */
    fun shed(step: ShedStep) {
        val codeView = view ?: return
        when (step) {
            ShedStep.LineLayoutCaches -> codeView.trimLayoutCaches()
            ShedStep.TokenizerCache -> codeView.trimTokenCache()
            else -> Unit
        }
    }

    /** The Code view left the composition. */
    fun detach(codeView: MarkdownCodeView) {
        if (view !== codeView) return
        // The selection outlives the view: the next one, or the buffer surface meanwhile, carries on from it.
        keepSelectionOf(codeView)
        view = null
    }

    /**
     * This session is no longer on screen: it stops listening to the buffer
     * and lets go of the Code view, which moves on to the next document
     * without a detach. The Preview may keep this session a while for late
     * page edits (DS-13); holding the view too would hold its Activity.
     */
    fun release() {
        view?.let { keepSelectionOf(it) }
        view = null
        matches = null
        text.removeEditObserver(opTap)
        outline.close()
        changes = null
        before = null
        applied.clear()
    }

    override fun onTextEdited(
        change: LineChange,
        version: Long,
    ) {
        open.onEditsApplied()
        outline.flush()
        compactor.maybeCompact()
        dispatchChanges()
        listener.onEdited()
    }

    override fun onSelectionChanged(
        start: Int,
        end: Int,
    ) = listener.onSelectionChanged(start, end)

    override fun onAnchorLineChanged(line: Int) = listener.onAnchorLineChanged(line)

    override fun onScrollFractionChanged(fraction: Float) = listener.onScrollFractionChanged(fraction)

    override fun onZoomChanged(
        percent: Int,
        pinching: Boolean,
    ) = listener.onZoomChanged(percent, pinching)

    override fun onCopyTooLarge(chars: Int) = listener.onCopyTooLarge(chars)

    override fun admitPaste(chars: Int): Boolean = listener.admitPaste(chars)

    override fun onPasteProgress(progress: PasteProgress) = listener.onPasteProgress(progress)

    override fun onShortcut(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
    ): Boolean = listener.onShortcut(keyCode, ctrl, shift)

    private fun afterBufferEdit(
        before: Snapshot,
        ops: List<EditOp>,
    ) {
        // The outline heard each op as it was applied (PERF-A: incremental, never a full re-read).
        outline.flush()
        compactor.maybeCompact()
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

/** Find matches as the Code view draws them: parallel offsets, and the current index or -1. */
private class FindHighlights(
    val starts: IntArray,
    val ends: IntArray,
    val current: Int,
)
