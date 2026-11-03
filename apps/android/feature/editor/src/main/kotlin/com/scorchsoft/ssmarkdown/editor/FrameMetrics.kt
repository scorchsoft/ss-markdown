package com.scorchsoft.ssmarkdown.editor

import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * The view's text metrics and per-frame values: row height, baseline and
 * gutter from the font, and the scroll and focus state the renderer draws
 * with. Filled in by the view, never reallocated per frame.
 */
internal class FrameMetrics(
    /** Display density, px per dp. */
    val density: Float,
) {
    var width = 0
    var height = 0
    var scrollX = 0
    var scrollY = 0
    var gutterWidth = 0
    var gap = 0

    /**
     * Whether the line-number gutter is drawn. Off, [gutterWidth] is 0 and
     * the text column starts [gap] in from the left edge, the same inset the
     * text keeps from the gutter when it is on.
     */
    var showGutter = true
    var baseline = 0f
    var focused = false
    var caretVisible = false

    /** Left edge of the text column, in view px. */
    val textLeft: Int get() = gutterWidth + gap

    /**
     * Recomputes the baseline, gutter and gap from [paint] for a document
     * of [lineCount] lines in cells [charWidth] px wide (a gutter of 0 when
     * [showGutter] is off); returns the row
     * height (glyph height × [LINE_SPACING], quality-bar's 1.6).
     */
    fun update(
        paint: Paint,
        charWidth: Float,
        lineCount: Int,
    ): Int {
        val metrics = paint.fontMetricsInt
        val glyphHeight = metrics.descent - metrics.ascent
        val rowHeight = (glyphHeight * LINE_SPACING).roundToInt().coerceAtLeast(1)
        baseline = (rowHeight - glyphHeight) / 2f - metrics.ascent
        gap = (GAP_DP * density).roundToInt()
        val digits = lineCount.toString().length.coerceAtLeast(MIN_GUTTER_DIGITS)
        gutterWidth = if (showGutter) (digits * charWidth).roundToInt() + 2 * gap else 0
        return rowHeight
    }

    /** Width of the text column inside the view, leaving a gap on the right. */
    fun textColumnWidth(): Int = (width - textLeft - gap).coerceAtLeast(0)

    private companion object {
        /** quality-bar typography: code line height 1.6. */
        const val LINE_SPACING = 1.6f
        const val GAP_DP = 8f
        const val MIN_GUTTER_DIGITS = 2
    }
}
