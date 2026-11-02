@file:Suppress("TooManyFunctions") // One small hook per collaborator call: the view's internal surface.

package com.scorchsoft.ssmarkdown.editor

import android.graphics.PointF
import android.graphics.Rect

// The code view's module-internal surface: hit testing in view px and the
// hooks its collaborators (touch, handles, the text toolbar, accessibility,
// commands) call. Kept beside MarkdownCodeView rather than in it, so the
// view class reads as the Android contract and these read as one idea:
// "where is what on screen".

internal val MarkdownCodeView.handles: SelectionHandles get() = selectionUi.handles

/** Baseline of a row below its top, px. */
internal val MarkdownCodeView.baseline: Float get() = frame.baseline

/** Lines currently laid out; lets tests prove drawing stays proportional to the screen. */
internal fun MarkdownCodeView.cachedLineCount(): Int = geometry.cachedLineCount

/** Lines recorded into display lists so far (hardware canvases only). */
internal fun MarkdownCodeView.recordedLineCount(): Int = renderer.recordedLines

/** Pastes [length] chars that [source] produces: at once when short, by slices when large. */
internal fun MarkdownCodeView.pasteText(
    length: Int,
    source: () -> CharSequence,
) {
    if (length in 0..ChunkedPaste.THRESHOLD_CHARS) {
        applyEdit(session.selectionStart, session.selectionEnd, source())
    } else if (length < 0 || listener?.admitPaste(length) != false) {
        paste.start(session.selectionStart, session.selectionEnd, source)
    }
}

internal fun MarkdownCodeView.offsetAtView(
    x: Float,
    y: Float,
): Int = geometry.offsetAt(x - textLeft + viewport.horizontalScroll(), scrollY.toLong() + y.toLong())

/** Where [offset] is drawn: x at the caret, y at the top of its row, in view px. */
internal fun MarkdownCodeView.pointInView(offset: Int): PointF {
    val point = geometry.locate(offset.coerceIn(0, session.document.text.length))
    return PointF(textLeft - viewport.horizontalScroll() + point.x, (point.top - scrollY).toFloat())
}

internal fun MarkdownCodeView.caretPointInView(): PointF? =
    pointInView(session.caret).takeIf {
        it.y in
            0f..height.toFloat()
    }

internal fun MarkdownCodeView.handlePoint(kind: HandleKind): PointF? = selectionUi.handlePoint(kind)

internal fun MarkdownCodeView.visibleLineRange(): IntRange = geometry.heights.visibleLines(scrollY, height)

/** Fills [out] with [line]'s bounds in view px; returns true if any of it is on screen. */
internal fun MarkdownCodeView.lineBoundsInView(
    line: Int,
    out: Rect,
): Boolean {
    val top = (geometry.heights.lineTop(line) - scrollY).toInt()
    out.set(textLeft, top, width, top + geometry.heights.heightOf(line))
    return out.bottom > 0 && out.top < height
}

internal fun MarkdownCodeView.runCommand(command: EditorCommand): Boolean = commands.run(command)

internal fun MarkdownCodeView.showTextActions() = selectionUi.showToolbar()

internal fun MarkdownCodeView.hideTextActions() = selectionUi.hideToolbar()

internal fun MarkdownCodeView.onTap(
    x: Float,
    y: Float,
) {
    selectionUi.hideToolbar()
    session.moveCaretTo(offsetAtView(x, y))
    selectionUi.setInsertionHandle(true)
}

internal fun MarkdownCodeView.selectWordAt(
    x: Float,
    y: Float,
) {
    requestFocus()
    session.selectWord(offsetAtView(x, y), words)
}

internal fun MarkdownCodeView.scrollByClamped(
    dx: Int,
    dy: Int,
) = viewport.scrollByClamped(dx, dy)

internal fun MarkdownCodeView.startFling(
    velocityX: Int,
    velocityY: Int,
) = viewport.startFling(velocityX, velocityY)

internal fun MarkdownCodeView.stopFling() = viewport.stopFling()

/** One pinch step; see [ViewSizing.zoomBy]. */
internal fun MarkdownCodeView.zoomBy(
    factor: Float,
    focusY: Float,
) = sizing.zoomBy(factor, focusY)

internal fun MarkdownCodeView.endPinch() = sizing.endPinch()
