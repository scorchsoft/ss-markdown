package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.fenceStates
import com.scorchsoft.ssmarkdown.editor.redo
import com.scorchsoft.ssmarkdown.editor.undo
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.markdown.edit.FenceStates
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.Snapshot

/**
 * Where programmatic edits go: the strip, find & replace, restore, merge.
 *
 * While the Code pane is on screen, edits must go **through the view**,
 * so its layout cache, tokenizer and keyboard connection see each change
 * ([CodeViewSurface]). With no view (Preview mode, tests, the share
 * sheet), they go straight to the store's buffer ([BufferSurface]).
 * Either way the store's [OpenDocument] journals every op as the buffer
 * applies it.
 */
interface EditorSurface {
    /** The current selection, ordered. */
    val selection: TextSelection

    /** Commits the keyboard's in-progress word (research R6; robustness §5a.4). */
    fun flushComposing()

    /** Applies [ops] (highest offset first) as one undo step, then selects [after]. */
    fun apply(
        ops: List<EditOp>,
        after: TextSelection,
    )

    /** Undoes one step; false when there was none. */
    fun undo(): Boolean

    /** Redoes one step; false when there was none. */
    fun redo(): Boolean

    /**
     * Selects `[start, end)` and scrolls the least distance that shows it
     * (its start, when it is taller than the pane). Callers that want a line
     * at the top follow it with [scrollToLine].
     */
    fun select(
        start: Int,
        end: Int,
    )

    /** Which lines are fenced code, for the ¶ menu's tick; [FenceStates.NONE] when nothing tracks it cheaply. */
    val fences: FenceStates get() = FenceStates.NONE

    /** Scrolls so [line] is at the top. */
    fun scrollToLine(line: Int)
}

/**
 * Fence state for an edit command over [text]: the Code view's tokenizer
 * when there is one, which already knows (a fresh scan reads the text from
 * the top, every tap, on the main thread), else a scan.
 */
fun EditorSurface.fencesFor(text: CharSequence): FenceStates =
    fences.takeUnless { it === FenceStates.NONE } ?: FenceStates.scan(text)

/** Edits through a live [MarkdownCodeView]; its listener journals them. */
class CodeViewSurface(
    private val view: MarkdownCodeView,
) : EditorSurface {
    override val selection: TextSelection
        get() = TextSelection(view.session.selectionStart, view.session.selectionEnd)

    override fun flushComposing() = view.requestFlushComposing()

    override fun apply(
        ops: List<EditOp>,
        after: TextSelection,
    ) {
        if (ops.isEmpty()) {
            select(after.start, after.end)
            return
        }
        view.applyEdits(ops, after.start, after.end)
    }

    override fun undo(): Boolean = view.completePaste() && view.session.undo()

    override fun redo(): Boolean = view.completePaste() && view.session.redo()

    override fun select(
        start: Int,
        end: Int,
    ) {
        view.session.setSelection(start, end)
        // The least scroll that shows the range, wrapped rows included. Putting its *line* at the top
        // left a find match deep in a long paragraph below the pane (edge-cases "Find next does not move").
        view.revealRange(start, end)
    }

    override fun scrollToLine(line: Int) = view.scrollToLine(line)

    /** From the view's own tokenizer, so reading it costs nothing per caret move. */
    override val fences: FenceStates get() = view.fenceStates()
}

/**
 * Edits straight into the store's buffer. The [OpenDocument] journals
 * each op as it is applied; [onChanged] lets the session refresh what it
 * derives (outline, the Preview) with the text before the change and the
 * ops applied to it, in order.
 */
class BufferSurface(
    private val document: OpenDocument,
    private val onChanged: (Snapshot, List<EditOp>) -> Unit,
) : EditorSurface {
    private var current = TextSelection.caret(0)

    override val selection: TextSelection get() = current

    override fun flushComposing() = Unit

    override fun apply(
        ops: List<EditOp>,
        after: TextSelection,
    ) {
        val text = document.document
        val before = text.snapshot()
        val applied = ArrayList<EditOp>(ops.size)
        text.beginEditGroup()
        try {
            ops.forEach { op -> document.replace(op.offset, op.deleted.length, op.inserted)?.let(applied::add) }
        } finally {
            text.endEditGroup()
        }
        current = after
        if (applied.isNotEmpty()) onChanged(before, applied)
    }

    override fun undo(): Boolean {
        val before = document.document.snapshot()
        val group = document.undo() ?: return false
        // Undo applies the group's inverses, last first.
        onChanged(before, group.ops.asReversed().map(EditOp::inverse))
        return true
    }

    override fun redo(): Boolean {
        val before = document.document.snapshot()
        val group = document.redo() ?: return false
        onChanged(before, group.ops)
        return true
    }

    override fun select(
        start: Int,
        end: Int,
    ) {
        current = TextSelection(start, end)
    }

    override fun scrollToLine(line: Int) = Unit
}
