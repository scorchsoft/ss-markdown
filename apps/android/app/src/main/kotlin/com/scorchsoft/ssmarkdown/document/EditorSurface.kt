package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.redo
import com.scorchsoft.ssmarkdown.editor.undo
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
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

    /** Selects `[start, end)` and scrolls it into view. */
    fun select(
        start: Int,
        end: Int,
    )

    /** Scrolls so [line] is at the top. */
    fun scrollToLine(line: Int)
}

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
        val document = view.session.document
        document.beginEditGroup()
        try {
            ops.forEach { view.session.applyEdit(it.offset, it.offset + it.deleted.length, it.inserted) }
        } finally {
            document.endEditGroup()
        }
        view.session.setSelection(after.start, after.end)
    }

    override fun undo(): Boolean = view.session.undo()

    override fun redo(): Boolean = view.session.redo()

    override fun select(
        start: Int,
        end: Int,
    ) {
        view.session.setSelection(start, end)
        view.scrollToLine(
            view.session.document.text
                .positionAt(start)
                .line,
        )
    }

    override fun scrollToLine(line: Int) = view.scrollToLine(line)
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
