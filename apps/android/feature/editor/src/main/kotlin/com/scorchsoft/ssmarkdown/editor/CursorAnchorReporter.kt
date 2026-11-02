package com.scorchsoft.ssmarkdown.editor

import android.graphics.Matrix
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection

/**
 * `InputConnection.requestCursorUpdates` for the code view: tells the
 * keyboard where the caret and the composing word are drawn, so
 * handwriting, floating and CJK keyboards place their candidate windows
 * beside the text instead of guessing.
 *
 * [request] answers an IMMEDIATE request at once and remembers a MONITOR
 * request; [onCursorMoved] then reports after every settled selection,
 * edit and scroll until the keyboard asks to stop (a request without
 * MONITOR). Reports go to [sink] (`InputMethodManager.updateCursorAnchorInfo`
 * in the view, a list in tests).
 */
internal class CursorAnchorReporter(
    private val view: MarkdownCodeView,
    private val sink: (CursorAnchorInfo) -> Unit,
) {
    /** True while the keyboard has asked to be told about every change. */
    var monitoring: Boolean = false
        private set

    private val location = IntArray(2)
    private val matrix = Matrix()

    /** Handles a `requestCursorUpdates(mode)` call; returns whether it is supported (it always is). */
    fun request(mode: Int): Boolean {
        monitoring = mode and InputConnection.CURSOR_UPDATE_MONITOR != 0
        if (mode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) sink(build())
        return true
    }

    /** The caret, selection, composing text or scroll changed. */
    fun onCursorMoved() {
        if (monitoring) sink(build())
    }

    /** The connection closed: stop reporting. */
    fun stop() {
        monitoring = false
    }

    /** The current state, in view-local px with the view-to-screen [Matrix]. */
    fun build(): CursorAnchorInfo {
        val session = view.session
        val builder = CursorAnchorInfo.Builder()
        builder.setSelectionRange(session.selectionStart, session.selectionEnd)
        if (session.isComposing) {
            val text = session.document.text
            builder.setComposingText(session.composingStart, text.getText(session.composingStart, session.composingEnd))
            addCharacterBounds(builder, session.composingStart, session.composingEnd)
        }
        val caret = view.pointInView(session.caret)
        val bottom = caret.y + view.rowHeight
        val visible = caret.y >= 0f && caret.y < view.height && caret.x >= view.textLeft && caret.x <= view.width
        val flags =
            if (visible) CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION else CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION
        builder.setInsertionMarkerLocation(caret.x, caret.y, caret.y + view.baseline, bottom, flags)
        view.getLocationOnScreen(location)
        matrix.setTranslate(location[0].toFloat(), location[1].toFloat())
        builder.setMatrix(matrix)
        return builder.build()
    }

    /** Bounds of each composing char, so the keyboard can underline or anchor to it. Capped: words are short. */
    private fun addCharacterBounds(
        builder: CursorAnchorInfo.Builder,
        start: Int,
        end: Int,
    ) {
        val last = minOf(end, start + MAX_CHARACTER_BOUNDS)
        for (index in start until last) {
            val left = view.pointInView(index)
            val right = view.pointInView(index + 1)
            val sameRow = right.y == left.y
            val rightX = if (sameRow) right.x else left.x + view.charWidth
            builder.addCharacterBounds(
                index,
                left.x,
                left.y,
                rightX,
                left.y + view.rowHeight,
                CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION,
            )
        }
    }

    private companion object {
        const val MAX_CHARACTER_BOUNDS = 256
    }
}
