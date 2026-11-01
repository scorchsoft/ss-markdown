package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.redo
import com.scorchsoft.ssmarkdown.editor.undo
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp

/**
 * Where programmatic edits go: the strip, find & replace, restore, merge.
 *
 * While the Code pane is on screen, edits must go **through the view**,
 * so its layout cache, tokenizer and keyboard connection see each change
 * ([CodeViewSurface]). With no view (Preview mode, tests, the share
 * sheet), they go straight to the store's buffer ([BufferSurface]),
 * which journals them itself.
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
 * Edits straight into the store's buffer. [OpenDocument.replace] journals
 * each op; [onChanged] lets the session refresh what it derives (outline,
 * the edit recorder's snapshot).
 */
class BufferSurface(
    private val document: OpenDocument,
    private val onChanged: () -> Unit,
) : EditorSurface {
    private var current = TextSelection.caret(0)

    override val selection: TextSelection get() = current

    override fun flushComposing() = Unit

    override fun apply(
        ops: List<EditOp>,
        after: TextSelection,
    ) {
        val text = document.document
        text.beginEditGroup()
        try {
            ops.forEach { document.replace(it.offset, it.deleted.length, it.inserted) }
        } finally {
            text.endEditGroup()
        }
        current = after
        if (ops.isNotEmpty()) onChanged()
    }

    override fun undo(): Boolean = (document.undo() != null).also { if (it) onChanged() }

    override fun redo(): Boolean = (document.redo() != null).also { if (it) onChanged() }

    override fun select(
        start: Int,
        end: Int,
    ) {
        current = TextSelection(start, end)
    }

    override fun scrollToLine(line: Int) = Unit
}
