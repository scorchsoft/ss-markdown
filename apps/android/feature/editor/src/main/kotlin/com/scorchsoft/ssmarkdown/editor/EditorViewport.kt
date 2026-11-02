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

    /**
     * Scroll position over the scrollable range, from the height map (no
     * measuring): 0 at the top or when it all fits, exactly 1 at the bottom.
     */
    fun scrollFraction(): Float {
        val max = maxScrollY()
        return if (max <= 0) 0f else (view.scrollY.toFloat() / max).coerceIn(0f, 1f)
    }

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
        if (y != view.scrollY) {
            // A correction is not the reader moving: it must not flip the direction
            // that prefetch and the render margin lean towards.
            correcting = true
            view.scrollTo(view.scrollX, y)
            correcting = false
            carryFlingThroughCorrection(y)
        }
        return geometry.heights.visibleLines(view.scrollY, height)
    }

    /**
     * Measuring lines above the top changes their height, and the scroll
     * anchoring above moves scrollY by the difference so the text on screen
     * stays put. A fling, though, sets scrollY from the scroller's own
     * position every frame, which knew nothing of the correction: the next
     * frame undid it and the text lurched by the correction, forwards or
     * backwards, on every frame that measured a line. That is a fling
     * towards the start, where the lines arriving are above the anchor;
     * flinging down measures below it and never corrects, which is why only
     * upward flicks juddered (edge-cases.md, "Upward fling judders").
     *
     * So a correction mid-fling restarts the fling from the corrected
     * position at the scroller's current velocity: it keeps decelerating
     * from the same speed, in the new coordinates, and still reaches the
     * real top.
     */
    private fun carryFlingThroughCorrection(y: Int) {
        if (scroller.isFinished) return
        val speed = scroller.currVelocity.roundToInt()
        val velocityY = if (scroller.finalY < scroller.currY) -speed else speed
        // Finished first: OverScroller's flywheel adds a same-direction fling's velocity to the old one.
        scroller.forceFinished(true)
        scroller.fling(view.scrollX, y, 0, velocityY, 0, if (geometry.wrap) 0 else maxScrollX(), 0, maxScrollY())
        flingRestarts++
    }

    private var correcting = false

    /** Flings carried through a scroll correction; tests read it. */
    var flingRestarts: Int = 0
        private set

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

    /** Stops a fling; true when one was still moving. */
    fun stopFling(): Boolean {
        val moving = !scroller.isFinished
        scroller.forceFinished(true)
        return moving
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
        if (correcting) return
        direction =
            when {
                top > oldTop -> ScrollDirection.TOWARDS_END
                top < oldTop -> ScrollDirection.TOWARDS_START
                else -> direction
            }
    }

    /**
     * Scrolls the least distance that shows `[start, end)` with [margin] px
     * of context above and below, or its start when it is taller than the
     * pane. Used for a find match or a selection set from outside, which may
     * sit many rows down a wrapped paragraph: placing the *line* at the top
     * would leave such a match below the pane (edge-cases "Find next does
     * not move"). Nothing moves when the range is already in view.
     *
     * The rows between the pane's new top and the range are measured first,
     * so the position is exact rather than an estimate that measuring
     * corrects a frame later; a screen of lines at most.
     */
    fun revealRange(
        start: Int,
        end: Int,
        rowHeight: Int,
        margin: Int,
    ) {
        if (view.height == 0) return
        stopFling()
        val firstLine = geometry.locate(start).line
        var line = firstLine
        var above = 0L
        while (line > 0 && above < view.height) {
            line--
            geometry.layoutFor(line)
            above += geometry.heights.heightOf(line)
        }
        val first = geometry.locate(start)
        val last = geometry.locate(maxOf(start, end))
        val gap = margin.coerceIn(0, ((view.height - rowHeight) / 2).coerceAtLeast(0)).toLong()
        val top = first.top - gap
        val bottom = last.top + rowHeight + gap
        val scrollY = view.scrollY.toLong()
        val y =
            when {
                // The start must show; past a pane's height, the end gives way.
                top < scrollY || bottom - top > view.height -> top

                bottom > scrollY + view.height -> bottom - view.height

                else -> scrollY
            }.coerceIn(0L, maxScrollY().toLong()).toInt()
        val textWidth = frame.textColumnWidth()
        val x =
            when {
                geometry.wrap -> 0
                first.x < view.scrollX -> first.x.toInt()
                first.x > view.scrollX + textWidth - frame.gap -> (first.x - textWidth / 2).roundToInt()
                else -> view.scrollX
            }.coerceAtLeast(0)
        if (x == view.scrollX && y == view.scrollY) return
        // The target came from the heights as they are now: corrections owed to the old position are moot.
        geometry.takeScrollShift()
        view.scrollTo(x, y)
        view.invalidate()
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
