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
