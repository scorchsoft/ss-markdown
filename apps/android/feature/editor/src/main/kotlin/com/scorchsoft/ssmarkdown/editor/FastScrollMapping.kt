package com.scorchsoft.ssmarkdown.editor

import android.view.View
import kotlin.math.floor

// The fast-scroll thumb's maths over a HeightMap, kept free of Android so it
// is tested on its own (docs/ux-spec.md, "Fast scroll"). Positions are
// fractional line indexes: the line at the pane's top plus how far down it
// the top is. A thumb fraction maps to a *line*, not a pixel, so a 20 MB
// file whose heights are mostly estimates lands on the same line however
// the heights change as lines are measured: monotonic, and no jitter.

/** The pane's top as a fractional line index, for scroll offset [scrollY]. */
fun HeightMap.topPosition(scrollY: Int): Double {
    val line = lineAt(scrollY.toLong())
    val height = heightOf(line)
    if (height <= 0) return line.toDouble()
    val within = (scrollY - lineTop(line)).toDouble() / height
    return line + within.coerceIn(0.0, 1.0)
}

/** The top position at the very bottom of the document: the end of the thumb's travel. */
fun HeightMap.endPosition(viewportHeight: Int): Double = topPosition(maxScrollY(viewportHeight))

/** The thumb fraction for [scrollY]: the top position over [end]. 0 when nothing scrolls. */
fun HeightMap.lineFraction(
    scrollY: Int,
    end: Double,
): Float = if (end <= 0.0) 0f else (topPosition(scrollY) / end).toFloat().coerceIn(0f, 1f)

/** How much of the document a pane [viewportHeight] px tall shows at once, 0 to 1. */
fun HeightMap.visibleFraction(viewportHeight: Int): Float {
    val content = contentHeight
    return if (content <= 0L) 1f else (viewportHeight.toDouble() / content).toFloat().coerceIn(0f, 1f)
}

/**
 * The fast-scroll thumb's view of a [MarkdownCodeView]: where the pane is,
 * as a line-index fraction, and the drags that move it. A drag places a
 * line at the top through [EditorViewport.placeLine], so the caret,
 * selection and text are untouched. The end of the travel is frozen for
 * the length of a drag, so measuring lines near the end mid-drag cannot
 * shift where a given finger position lands.
 *
 * Nothing here allocates per frame.
 */
class CodeFastScroll internal constructor(
    private val view: View,
    private val geometry: EditorGeometry,
    private val viewport: EditorViewport,
) {
    private var frozenEnd = NOT_DRAGGING

    /** True between [begin] and [end]. */
    val isDragging: Boolean get() = frozenEnd != NOT_DRAGGING

    /** The pane's position for the thumb, 0 at the top and 1 at the very bottom. */
    val fraction: Float
        get() = geometry.heights.lineFraction(view.scrollY, travelEnd())

    /** How much of the document the pane shows at once, 0 to 1. */
    val visibleFraction: Float get() = geometry.heights.visibleFraction(view.height)

    /** A finger took the thumb: stops any fling and freezes the end of the travel. */
    fun begin() {
        viewport.stopFling()
        frozenEnd = geometry.heights.endPosition(view.height)
    }

    /** Puts the pane at [fraction] of the document, by line index. */
    fun dragTo(fraction: Float) {
        val heights = geometry.heights
        if (fraction >= 1f) {
            // The very bottom, whatever the heights near the end turned out to be once measured.
            // The last screen is measured first (a screen of lines at most), so the bottom is exact.
            val last = heights.lineCount - 1
            var line = last
            var filled = 0L
            while (line >= 0 && filled < view.height) {
                geometry.layoutFor(line)
                filled += heights.heightOf(line)
                line--
            }
            viewport.placeLine(last, (view.height - heights.heightOf(last)).toFloat())
            return
        }
        val position = fraction.coerceIn(0f, 1f) * travelEnd()
        val line = floor(position).toInt().coerceIn(0, heights.lineCount - 1)
        // Measured first, so the share of the line above the top uses its real height.
        geometry.layoutFor(line)
        val within = (position - line).coerceIn(0.0, 1.0)
        viewport.placeLine(line, -(within * heights.heightOf(line)).toFloat())
    }

    /** The finger lifted: the pane stays where it is. */
    fun end() {
        frozenEnd = NOT_DRAGGING
    }

    private fun travelEnd(): Double = if (isDragging) frozenEnd else geometry.heights.endPosition(view.height)

    private companion object {
        const val NOT_DRAGGING = -1.0
    }
}
