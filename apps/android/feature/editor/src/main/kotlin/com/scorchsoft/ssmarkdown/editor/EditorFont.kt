package com.scorchsoft.ssmarkdown.editor

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue

/**
 * The Code pane's one text paint and what follows from it: the cell width
 * ([measurer]), the row height, baseline and gutter ([FrameMetrics]), and
 * the zoom that sets its size (docs/ux-spec.md, "Text size: one model").
 * Split out of [MarkdownCodeView] so a font change has one place to go.
 */
internal class EditorFont(
    private val resources: Resources,
    private val frame: FrameMetrics,
) {
    val paint: Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = spToPx(ZoomModel.BASE_SP)
        }

    val measurer: LineMeasurer = LineMeasurer(paint)

    val zoom: ZoomModel = ZoomModel()

    /** Sets the paint to the zoom's size; the caller then relayouts. */
    fun applyZoomSize() {
        paint.textSize = spToPx(zoom.textSizeSp)
    }

    /** Re-reads the paint after its size or typeface changed. */
    fun onPaintChanged() {
        measurer.onPaintChanged()
    }

    /**
     * Recomputes row height, baseline and gutter for a view [width] px wide
     * and a document of [lineCount] lines; returns the row height.
     */
    fun updateMetrics(
        width: Int,
        lineCount: Int,
    ): Int {
        frame.width = width
        return frame.update(paint, measurer.charWidth, lineCount)
    }

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}

/** The caret's blink: on for half a second, off for half a second, restarted by every caret move. */
internal class CaretBlink(
    private val view: MarkdownCodeView,
    private val frame: FrameMetrics,
) : Runnable {
    override fun run() {
        frame.caretVisible = !frame.caretVisible
        view.invalidate()
        view.postDelayed(this, BLINK_MILLIS)
    }

    /** Shows the caret now and blinks from here while the view has focus. */
    fun restart() {
        view.removeCallbacks(this)
        frame.caretVisible = true
        if (view.hasFocus()) view.postDelayed(this, BLINK_MILLIS)
    }

    fun stop() {
        view.removeCallbacks(this)
    }

    private companion object {
        const val BLINK_MILLIS = 500L
    }
}
