package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.text.PieceTree

/**
 * Where every line, row and column of the document sits, in content px,
 * for both No-wrap and Wrap modes; the maths the view, the selection
 * handles and accessibility all share.
 *
 * - Lays out lines on demand through the [LineLayoutCache] (a window of a
 *   long line in No wrap, rows broken to [textWidth] in Wrap).
 * - Keeps the [HeightMap] in step: in Wrap mode a line's rows are recorded
 *   the first time it is laid out. A line above the [anchorLine] that
 *   changes height adds to [pendingScrollShift], which the view adds to its
 *   scroll position before drawing (`newTop − oldTop`), so what is on
 *   screen never jumps.
 *
 * Content coordinates: x from the left of the text column (before any
 * horizontal scroll), y from the top of line 0.
 */
internal class EditorGeometry(
    private val measurer: LineMeasurer,
    /** The live text and its edit version; read on every call so edits are seen at once. */
    private val textSource: () -> PieceTree,
    private val versionSource: () -> Long,
) {
    /** The per-line layouts; memory pressure clears it (heights are kept, so nothing on screen moves). */
    val cache = LineLayoutCache()

    /** Line tops and heights. */
    var heights: HeightMap = HeightMap(1, 1)
        private set

    /** True in Wrap mode. Change it through [configure]. */
    var wrap: Boolean = false
        private set

    /** Width of the text column, px; the wrap width. */
    var textWidth: Int = 0
        private set

    /** Horizontal scroll in No-wrap mode; picks each long line's window. */
    var scrollX: Int = 0

    /** The first visible line; lines above it shift the scroll when they change height. */
    var anchorLine: Int = 0

    /** Px the scroll position must move to keep the anchor still; consumed by [takeScrollShift]. */
    private var pendingScrollShift = 0L

    /** Lines currently laid out; tests prove drawing stays proportional to the screen. */
    val cachedLineCount: Int get() = cache.size

    private val text: PieceTree get() = textSource()

    /** Resets every layout and height for [rowHeight], [wrap] and [textWidth]; scroll is the caller's to restore. */
    fun configure(
        rowHeight: Int,
        wrap: Boolean,
        textWidth: Int,
    ) {
        this.wrap = wrap
        this.textWidth = textWidth
        cache.clear()
        heights = HeightMap(text.lineCount, rowHeight)
        pendingScrollShift = 0
    }

    /** Follows an edit reported by the session. */
    fun onEdit(change: LineChange) {
        cache.onEdit(change.firstLine, change.removedBreaks, change.addedBreaks, versionSource())
        heights.replaceLines(change.firstLine, change.removedBreaks + 1, change.addedBreaks + 1)
    }

    /** The layout of [line], measured now if not cached. */
    fun layoutFor(line: Int): LineRows {
        val key = windowKey(line)
        val version = versionSource()
        cache.get(line, version, key)?.let { return it }
        val layout =
            if (wrap) {
                measurer.measureWrapped(text, line, textWidth.toFloat())
            } else {
                measurer.measure(text, line, key)
            }
        cache.put(line, version, key, layout)
        if (wrap) {
            val moved = heights.setRows(line, layout.rowCount)
            if (line < anchorLine) pendingScrollShift += moved
        }
        return layout
    }

    /** True when [line] is cached for the current window and version. */
    fun isLaidOut(line: Int): Boolean = cache.get(line, versionSource(), windowKey(line)) != null

    /** Returns and clears the scroll correction accumulated by layouts above the anchor. */
    fun takeScrollShift(): Int {
        val shift = pendingScrollShift
        pendingScrollShift = 0
        return shift.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Lays out the lines a viewport at [scrollY] of [viewportHeight] px
     * shows, keeping the line at its top in place, and returns the
     * corrected scrollY. In Wrap mode, measuring can shrink lines and
     * reveal more, so it repeats, at most [MAX_PASSES] times and
     * [MAX_LINES_PER_PASS] lines a pass (CodeMirror's measure-loop cap).
     * Also refines [HeightMap.estimatedRows] from what has been measured.
     */
    fun settle(
        scrollY: Int,
        viewportHeight: Int,
    ): Int {
        var y = scrollY.toLong()
        repeat(MAX_PASSES) {
            val anchor = heights.lineAt(y)
            val offsetInLine = y - heights.lineTop(anchor)
            anchorLine = anchor
            var line = anchor
            var bottom = heights.lineTop(anchor)
            var count = 0
            while (line < heights.lineCount && bottom < y + viewportHeight && count < MAX_LINES_PER_PASS) {
                layoutFor(line)
                bottom += heights.heightOf(line)
                line++
                count++
            }
            // Unmeasured lines are assumed to be as tall as the measured ones on average.
            if (wrap && heights.measuredLineCount >= MIN_LINES_FOR_ESTIMATE) {
                heights.estimatedRows = heights.averageMeasuredRows
            }
            val corrected = heights.lineTop(anchor) + offsetInLine.coerceAtMost(heights.heightOf(anchor) - 1L)
            if (corrected == y) return y.toInt()
            y = corrected
        }
        return y.toInt()
    }

    /** The document offset at content point ([x], [y]); below the last line, the end of the document. */
    fun offsetAt(
        x: Float,
        y: Long,
    ): Int {
        if (y >= heights.contentHeight) return text.length
        val line = heights.lineAt(y)
        val layout = layoutFor(line)
        val row = ((y - heights.lineTop(line)) / heights.rowHeight).toInt().coerceIn(0, layout.rowCount - 1)
        return text.lineStartOffset(line) + layout.columnAt(row, x)
    }

    /** Where [offset] is drawn: its line, row, the row's content top, and x from the text column's left. */
    fun locate(offset: Int): CaretPoint {
        val position = text.positionAt(offset)
        val layout = layoutFor(position.line)
        val row = layout.rowOf(position.column)
        val top = heights.lineTop(position.line) + row.toLong() * heights.rowHeight
        return CaretPoint(position.line, row, top, layout.xOf(position.column))
    }

    /**
     * The offset [rows] visual rows above (negative) or below the one
     * holding [offset], at x [goalX]: Up/Down and Page Up/Down in either mode.
     */
    fun offsetByRows(
        offset: Int,
        rows: Int,
        goalX: Float,
    ): Int {
        val position = text.positionAt(offset)
        var line = position.line
        var row = layoutFor(line).rowOf(position.column) + rows
        while (row < 0 && line > 0) {
            line--
            row += layoutFor(line).rowCount
        }
        while (line < text.lineCount - 1 && row >= layoutFor(line).rowCount) {
            row -= layoutFor(line).rowCount
            line++
        }
        val layout = layoutFor(line)
        val target = row.coerceIn(0, layout.rowCount - 1)
        return text.lineStartOffset(line) + layout.columnAt(target, goalX)
    }

    private fun windowKey(line: Int): Int =
        if (wrap) {
            textWidth
        } else {
            val centre = ((scrollX + textWidth / 2) / measurer.charWidth.coerceAtLeast(1f)).toInt()
            LineWindow.startFor(text.lineLength(line), centre)
        }

    private companion object {
        const val MAX_PASSES = 3
        const val MAX_LINES_PER_PASS = 500
        const val MIN_LINES_FOR_ESTIMATE = 16
    }
}

/** Where a caret offset is drawn: [line], visual [row], the row's content [top], and [x] within the row. */
internal data class CaretPoint(
    val line: Int,
    val row: Int,
    val top: Long,
    val x: Float,
)
