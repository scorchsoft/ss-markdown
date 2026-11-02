package com.scorchsoft.ssmarkdown.editor

import android.widget.OverScroller
import kotlin.math.roundToInt

/**
 * The view's scroll position and the layout pass that settles it, split
 * out of [MarkdownCodeView] so the view is the Android contract and this
 * is the maths: which lines are on screen, where a line is placed after a
 * relayout or zoom, how far the content scrolls, and how the caret is
 * revealed.
 *
 * It scrolls the view through `scrollTo`, so `View.scrollX/Y` stay the
 * single source of truth that accessibility, handles and touch all read.
 */
@Suppress("TooManyFunctions") // Scroll, fling, relayout and reveal: each a few lines over the same state.
internal class EditorViewport(
    private val view: MarkdownCodeView,
    private val geometry: EditorGeometry,
    private val frame: FrameMetrics,
    private val scroller: OverScroller,
) {
    /** Which way the content last scrolled; prefetch and the render margin lean that way. */
    var direction: ScrollDirection = ScrollDirection.IDLE
        private set

    /** Width of the widest row drawn last frame; bounds horizontal scrolling in No wrap. */
    var widestVisibleRow: Float = 0f

    /** The source line at the top of the pane. */
    val anchorLine: Int get() = geometry.heights.lineAt(view.scrollY.toLong())

    /** Horizontal scroll that applies: always 0 in Wrap mode. */
    fun horizontalScroll(): Int = if (geometry.wrap) 0 else view.scrollX

    fun maxScrollY(): Int = geometry.heights.maxScrollY(view.height)

    fun maxScrollX(): Int = (widestVisibleRow + frame.gap - frame.textColumnWidth()).roundToInt().coerceAtLeast(0)

    /** Throws away layouts and heights for [wrap], then scrolls so [line] sits at view y [viewY] again. */
    fun relayout(
        line: Int,
        viewY: Float,
        wrap: Boolean,
    ) {
        frame.width = view.width
        geometry.configure(geometry.heights.rowHeight, wrap, frame.textColumnWidth())
        placeLine(line, viewY)
    }

    /** Scrolls so the top of [line] is at view y [viewY], from the heights as they are now. */
    fun placeLine(
        line: Int,
        viewY: Float,
    ) {
        val target = line.coerceIn(0, geometry.heights.lineCount - 1)
        geometry.anchorLine = target
        geometry.layoutFor(target)
        val y = (geometry.heights.lineTop(target) - viewY.toLong()).coerceIn(0, maxScrollY().toLong())
        view.scrollTo(horizontalScroll(), y.toInt())
        // The position was computed from the current tops, so there is nothing left to correct.
        geometry.takeScrollShift()
        view.invalidate()
    }

    /**
     * Settles the frame before drawing: lays out the margin and the visible
     * rows, applies the scroll anchoring that measuring caused, and returns
     * the visible lines.
     */
    fun settle(prefetchLines: Int): IntRange {
        val height = view.height
        geometry.scrollX = horizontalScroll()
        geometry.anchorLine = geometry.heights.lineAt(view.scrollY.toLong())
        val margin = geometry.heights.renderLines(view.scrollY, height, direction)
        geometry.prefetch(geometry.heights.visibleLines(view.scrollY, height), margin, direction, prefetchLines)
        var y = view.scrollY + geometry.takeScrollShift()
        y = geometry.settle(y, height).coerceIn(0, maxScrollY())
        if (y != view.scrollY) view.scrollTo(view.scrollX, y)
        return geometry.heights.visibleLines(view.scrollY, height)
    }

    fun scrollByClamped(
        dx: Int,
        dy: Int,
    ) {
        val x = if (geometry.wrap) 0 else (view.scrollX + dx).coerceIn(0, maxScrollX())
        view.scrollTo(x, (view.scrollY + dy).coerceIn(0, maxScrollY()))
    }

    fun startFling(
        velocityX: Int,
        velocityY: Int,
    ) {
        val maxX = if (geometry.wrap) 0 else maxScrollX()
        scroller.fling(view.scrollX, view.scrollY, velocityX, velocityY, 0, maxX, 0, maxScrollY())
        view.postInvalidateOnAnimation()
    }

    fun stopFling() {
        scroller.forceFinished(true)
    }

    /** One animation step of a fling; true while it is still moving. */
    fun stepFling(): Boolean {
        if (!scroller.computeScrollOffset()) return false
        val x = if (geometry.wrap) 0 else scroller.currX.coerceIn(0, maxScrollX())
        view.scrollTo(x, scroller.currY.coerceIn(0, maxScrollY()))
        return true
    }

    /** Tracks the anchor line and the scroll direction. */
    fun onScrolled(
        top: Int,
        oldTop: Int,
    ) {
        geometry.anchorLine = geometry.heights.lineAt(top.toLong())
        direction =
            when {
                top > oldTop -> ScrollDirection.TOWARDS_END
                top < oldTop -> ScrollDirection.TOWARDS_START
                else -> direction
            }
    }

    /** Scrolls the least distance that shows [caret] with its row. */
    fun reveal(
        caret: Int,
        rowHeight: Int,
    ) {
        if (view.height == 0) return
        val point = geometry.locate(caret)
        val y = revealScroll(point.top, point.top + rowHeight, view.scrollY, view.height)
        val textWidth = frame.textColumnWidth()
        val x =
            when {
                geometry.wrap -> 0
                point.x < view.scrollX -> point.x.toInt()
                point.x > view.scrollX + textWidth - frame.gap -> (point.x - textWidth + frame.gap).roundToInt()
                else -> view.scrollX
            }
        if (x != view.scrollX || y != view.scrollY) view.scrollTo(x.coerceAtLeast(0), y)
    }
}
