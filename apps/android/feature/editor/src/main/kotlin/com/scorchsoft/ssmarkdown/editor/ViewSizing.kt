package com.scorchsoft.ssmarkdown.editor

import android.view.HapticFeedbackConstants

/**
 * What happens when the text's size or the view's width changes: the
 * font is re-read, metrics recomputed, layouts, heights and cached
 * drawings thrown away, and the scroll put back so the line that was
 * under the user's eyes (or fingers, for a pinch) stays there.
 */
internal class ViewSizing(
    private val view: MarkdownCodeView,
) {
    /** Applies the zoom's size, keeping the line at view y [viewY] in place. */
    fun applyZoom(viewY: Float) {
        val line = view.geometry.heights.lineAt(view.scrollY.toLong() + viewY.toLong())
        view.font.applyZoomSize()
        onFontChanged(line, viewY)
    }

    /** The paint changed: re-measure everything and put [line] back at view y [viewY]. */
    fun onFontChanged(
        line: Int,
        viewY: Float,
    ) {
        view.font.onPaintChanged()
        view.renderer.onPaintChanged(view.font.paint)
        onMetricsChanged()
        relayout(line, viewY)
    }

    /** Recomputes row height, baseline and gutter; returns true if the text column's width changed. */
    fun onMetricsChanged(): Boolean {
        val geometry = view.geometry
        geometry.heights.rowHeight = view.font.updateMetrics(view.width, view.session.document.text.lineCount)
        return view.frame.textColumnWidth() != geometry.textWidth
    }

    /**
     * The gutter was shown or hidden: the text column's width changed, so
     * every row wraps again. A caret on screen keeps its row at the same
     * view y, as near as the new wrapping allows (a row that no longer
     * exists lands where the caret's new row is); otherwise the top line
     * keeps its place, part-scrolled offset included, so the anchor
     * Preview follows does not move.
     */
    fun onGutterChanged() {
        // A fling would carry on from coordinates the relayout no longer uses.
        view.viewport.stopFling()
        val caret = view.session.caret
        val caretY = view.yForOffset(caret)
        val caretShown = view.height > 0 && caretY >= 0f && caretY < view.height
        val top = view.anchorLine
        val topY = (view.geometry.heights.lineTop(top) - view.scrollY).toFloat()
        onMetricsChanged()
        if (!caretShown) {
            relayout(top, topY)
            return
        }
        val line = view.geometry.locate(caret).line
        relayout(line, caretY)
        // The rows between the pane's top and the caret are measured now, or measuring them while
        // drawing would push the caret down the screen; the anchoring keeps the top line, not this one.
        view.viewport.measureAbove(line, view.height)
        // The relayout made a new height map: read it, not the one from before.
        val rowOffset = view.geometry.locate(caret).top - view.geometry.heights.lineTop(line)
        view.viewport.placeLine(line, caretY - rowOffset)
    }

    /** Throws away layouts, heights and drawings, then scrolls so [line] sits at view y [viewY] again. */
    fun relayout(
        line: Int,
        viewY: Float,
    ) {
        view.renderer.invalidateDrawings()
        view.viewport.relayout(line, viewY, view.wrap)
    }

    /** One pinch step: scales the font about view y [focusY], keeping the line under the fingers still. */
    fun zoomBy(
        factor: Float,
        focusY: Float,
    ) {
        val zoom = view.font.zoom
        val before = zoom.percent
        if (!zoom.scaleBy(factor)) return
        if (ZoomModel.crossesDefault(
                before,
                zoom.percent,
            )
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        applyZoom(focusY)
        view.listener?.onZoomChanged(zoom.percent, pinching = true)
    }

    fun endPinch() {
        view.listener?.onZoomChanged(view.font.zoom.percent, pinching = false)
    }
}
