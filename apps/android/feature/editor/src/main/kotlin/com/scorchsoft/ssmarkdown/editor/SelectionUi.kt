package com.scorchsoft.ssmarkdown.editor

import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.view.ActionMode

/**
 * The selection chrome around the text: which handles show and where
 * their points are, and the floating text toolbar. The insertion handle
 * appears after a tap and fades out after [INSERTION_HANDLE_MILLIS] or at
 * the next keystroke, as in `TextView`; the start and end handles show for
 * as long as text is selected.
 */
internal class SelectionUi(
    private val view: MarkdownCodeView,
    private val commands: CommandRunner,
) : TextActionModeCallback.Host {
    /** Shape, drawing and hit-testing of the handles. */
    val handles: SelectionHandles =
        view.resources.displayMetrics.density.let { density ->
            SelectionHandles(size = HANDLE_DP * density, touchSize = TOUCH_DP * density)
        }

    private val callback = TextActionModeCallback(this)
    private var actionMode: ActionMode? = null
    private var insertionVisible = false
    private val hideInsertion = Runnable { setInsertionHandle(false) }

    /** True while the floating toolbar is up. */
    val isToolbarShown: Boolean get() = actionMode != null

    override val hasSelection: Boolean get() = view.session.hasSelection

    override val canPaste: Boolean get() = commands.canPaste

    override fun runCommand(command: EditorCommand): Boolean = commands.run(command)

    override fun onActionModeDestroyed() {
        actionMode = null
    }

    /** Shows (or hides) the insertion handle under a bare caret; it hides itself after a pause. */
    fun setInsertionHandle(visible: Boolean) {
        view.removeCallbacks(hideInsertion)
        insertionVisible = visible && view.session.hasSelection.not()
        if (insertionVisible) view.postDelayed(hideInsertion, INSERTION_HANDLE_MILLIS)
        view.invalidate()
    }

    /** Shows the toolbar for the current selection, or moves it if it is up. */
    fun showToolbar() {
        if (!view.session.hasSelection && !insertionVisible) return
        val mode = actionMode
        if (mode == null) {
            actionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        } else {
            mode.invalidate()
            mode.invalidateContentRect()
        }
    }

    /** Hides the toolbar (scrolling, typing, dragging a handle). */
    fun hideToolbar() {
        actionMode?.finish()
        actionMode = null
    }

    /** Where the [kind] handle's point is in view px, or null when that handle is not showing. */
    fun handlePoint(kind: HandleKind): PointF? {
        val session = view.session
        val offset =
            when {
                !view.hasFocus() -> null
                kind == HandleKind.INSERTION -> session.caret.takeIf { insertionVisible && !session.hasSelection }
                !session.hasSelection -> null
                kind == HandleKind.START -> session.selectionStart
                else -> session.selectionEnd
            }
        val point = offset?.let(view::pointInView)
        return point?.takeIf { it.y + view.rowHeight >= 0 && it.y <= view.height }?.apply { y += view.rowHeight }
    }

    /** Draws every showing handle in [color]. */
    fun drawHandles(
        canvas: Canvas,
        color: Int,
    ) {
        for (kind in HandleKind.entries) {
            val point = handlePoint(kind) ?: continue
            handles.draw(canvas, kind, point.x, point.y, color)
        }
    }

    override fun selectionBounds(out: Rect) {
        val session = view.session
        val start = view.pointInView(session.selectionStart)
        val end = view.pointInView(session.selectionEnd)
        val top = minOf(start.y, end.y).toInt()
        val bottom = (maxOf(start.y, end.y) + view.rowHeight).toInt()
        val sameRow = start.y == end.y
        val left = if (sameRow) minOf(start.x, end.x).toInt() else view.textLeft
        val right = if (sameRow) maxOf(start.x, end.x).toInt() + 1 else view.width
        out.set(left, top.coerceAtLeast(0), right, bottom.coerceAtMost(view.height))
    }

    private companion object {
        const val HANDLE_DP = 22f
        const val TOUCH_DP = 48f
        const val INSERTION_HANDLE_MILLIS = 4_000L
    }
}
