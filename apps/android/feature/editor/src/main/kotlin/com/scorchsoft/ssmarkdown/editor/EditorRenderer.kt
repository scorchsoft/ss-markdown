package com.scorchsoft.ssmarkdown.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderNode
import com.scorchsoft.ssmarkdown.markdown.LineKind
import com.scorchsoft.ssmarkdown.markdown.LineResult

/**
 * Draws the visible rows: code-block bands, selection and find matches,
 * syntax-coloured text runs, the composing underline and current-match
 * outline, the caret and the gutter. Holds the paints; everything it draws
 * comes from the [EditorGeometry], the [EditingSession], the
 * [HighlightScheduler] and the [FindHighlights].
 *
 * On a hardware canvas each line's text is recorded once into a
 * `RenderNode` and replayed while the line, its tokens and the font stay
 * the same ([LineDrawCache], keyed by [LineVersions]), so scrolling
 * replays display lists instead of re-shaping text. A software canvas
 * (screenshots, tests) and very tall wrapped lines draw directly.
 */
@Suppress("TooManyFunctions") // One small function per layer of a row, in drawing order.
internal class EditorRenderer(
    private val geometry: EditorGeometry,
    private val session: EditingSession,
    private val highlight: HighlightScheduler,
    /** The text paint the measurer uses; runs restyle a copy so measurement never changes. */
    basePaint: Paint,
    private val finds: FindHighlights = FindHighlights(),
) {
    /** The colours and the styles built from them. Setting them redraws every cached line. */
    var styles: SyntaxStyles = SyntaxStyles(EditorTheme.LIGHT)
        set(value) {
            field = value
            invalidateDrawings()
        }

    /** Line identities for the drawing cache; the view keeps them in step with edits. */
    val versions = LineVersions()

    // hasDisplayList: the platform discards a node's display list once nothing draws it (a line
    // scrolled off screen), and the docs ask for it to be checked before every draw.
    private val nodes =
        LineDrawCache<RenderNode>(NODE_CAPACITY, isLive = { it.hasDisplayList() }) { it.discardDisplayList() }
    private var epoch = 0
    private val outline = Paint().apply { style = Paint.Style.STROKE }

    /** Lines whose text was recorded into a display list; tests and the benchmark read it. */
    val recordedLines: Int get() = nodes.recordings

    /** Lines recorded again because the platform had emptied their display list; tests read it. */
    val revivedLines: Int get() = nodes.revived

    /** Empties every cached display list, as the platform does to nodes nothing draws; tests use it. */
    fun discardDisplayListsForTest() = nodes.forEachDrawing { it.discardDisplayList() }

    /** Cached lines whose display list has content; tests read it. */
    fun liveLinesForTest(): Int {
        var live = 0
        nodes.forEachDrawing { if (it.hasDisplayList()) live++ }
        return live
    }

    /** Rows drawn in visual (bidi) order; stays 0 while every line is left to right. Tests read it. */
    var bidiRowsDrawn: Int = 0
        private set

    /** Forgets every cached drawing: the font, theme or wrap width changed. */
    fun invalidateDrawings() {
        epoch++
        nodes.clear()
    }

    private val textPaint = Paint(basePaint)
    private val gutter = GutterPainter(basePaint)
    private val fill = Paint()
    private var scratch = CharArray(SCRATCH_CHARS)
    private val row = RowCursor()

    /** The row being drawn; fields rather than parameters so the draw path allocates nothing. */
    private class RowCursor {
        var canvas: Canvas? = null
        var line = 0
        var lineStart = 0
        var layout: LineRows = LineLayout(0, 0, CharArray(0), FloatArray(1), 1f)
        var index = 0
        var top = 0f
        var bottom = 0f
        var left = 0f
        var baseline = 0f
        var chars: CharArray = CharArray(0)
        var charsStart = 0
    }

    /** Call after the base paint's size or typeface changed. */
    fun onPaintChanged(basePaint: Paint) {
        textPaint.set(basePaint)
        gutter.onPaintChanged(basePaint)
        invalidateDrawings()
    }

    /** Draws [visible] lines; the canvas is in view coordinates (scroll already undone). */
    fun draw(
        canvas: Canvas,
        visible: IntRange,
        frame: FrameMetrics,
    ) {
        val theme = styles.theme
        halfCell = frame.baseline / 2
        // The page is the view's rectangle, never drawColor: that fills the whole clip, and a
        // host that draws the view without clipping it first (a software capture of the Compose
        // screen) lost its header under the page (PaintBoundsTest).
        fill.color = theme.background
        canvas.drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), fill)
        canvas.save()
        canvas.clipRect(frame.gutterWidth, 0, frame.width, frame.height)
        for (line in visible) drawLine(canvas, line, frame)
        drawCaret(canvas, frame)
        canvas.restore()
        gutter.draw(canvas, visible, frame, styles.theme, geometry.heights)
        row.canvas = null
    }

    private fun drawLine(
        canvas: Canvas,
        line: Int,
        frame: FrameMetrics,
    ) {
        val layout = geometry.layoutFor(line)
        val heights = geometry.heights
        val top = heights.lineTop(line) - frame.scrollY
        val result = highlight.resultAt(line)
        if (result.kind == LineKind.CODE || result.kind == LineKind.CODE_FENCE) {
            fill.color = styles.theme.codeBlockBackground
            val bottom = (top + heights.heightOf(line)).toFloat()
            canvas.drawRect(frame.gutterWidth.toFloat(), top.toFloat(), frame.width.toFloat(), bottom, fill)
        }
        val rowHeight = heights.rowHeight
        val firstRow = ((-top) / rowHeight).toInt().coerceIn(0, layout.rowCount - 1)
        val lastRow = ((frame.height - top) / rowHeight).toInt().coerceIn(0, layout.rowCount - 1)
        row.canvas = canvas
        row.line = line
        row.lineStart = session.document.text.lineStartOffset(line)
        row.layout = layout
        row.left = frame.textLeft - (if (geometry.wrap) 0 else frame.scrollX).toFloat()
        for (index in firstRow..lastRow) {
            placeRow(index, top)
            drawUnderlays()
        }
        if (canvas.isHardwareAccelerated && layout.rowCount <= MAX_CACHED_ROWS) {
            drawCachedText(canvas, line, top, result, frame)
            row.canvas = canvas
        } else {
            for (index in firstRow..lastRow) {
                placeRow(index, top)
                drawRowText(result.tokens, frame)
            }
        }
        for (index in firstRow..lastRow) {
            placeRow(index, top)
            drawOverlays(frame)
        }
    }

    private fun placeRow(
        index: Int,
        lineTop: Long,
    ) {
        row.index = index
        row.top = (lineTop + index.toLong() * geometry.heights.rowHeight).toFloat()
        row.bottom = row.top + geometry.heights.rowHeight
    }

    /** Selection and find-match fills, under the text. */
    private fun drawUnderlays() {
        if (session.hasSelection) {
            fill.color = styles.theme.selection
            highlightSpan(session.selectionStart, session.selectionEnd, 0f, tail = true)
        }
        forEachMatchOnRow { index ->
            fill.color = if (index == finds.current) styles.theme.findCurrent else styles.theme.findMatch
            highlightSpan(finds.startOf(index), finds.endOf(index), 0f, tail = false)
        }
    }

    /** The composing underline and the current match's outline, over the text. */
    private fun drawOverlays(frame: FrameMetrics) {
        if (session.isComposing) {
            fill.color = styles.theme.composingUnderline
            highlightSpan(session.composingStart, session.composingEnd, UNDERLINE_DP * frame.density, tail = false)
        }
        val current = finds.current
        if (current < 0) return
        forEachMatchOnRow { index ->
            if (index == current) outlineSpan(finds.startOf(index), finds.endOf(index), OUTLINE_DP * frame.density)
        }
    }

    /** Calls [action] with every find match on the current row, found by binary search. */
    private inline fun forEachMatchOnRow(action: (Int) -> Unit) {
        if (finds.count == 0) return
        val rowStart = row.lineStart + row.layout.rowStart(row.index)
        val rowEnd = row.lineStart + row.layout.rowEnd(row.index)
        var index = finds.firstEndingAfter(rowStart)
        while (index < finds.count && finds.startOf(index) <= rowEnd) {
            action(index)
            index++
        }
    }

    /** Replays [line]'s recorded text, recording it first if the line, its tokens or the font changed. */
    private fun drawCachedText(
        canvas: Canvas,
        line: Int,
        top: Long,
        result: LineResult,
        frame: FrameMetrics,
    ) {
        probe.set(result, row.layout, epoch, row.left, frame.width)
        recordResult = result
        recordFrame = frame
        val node = nodes.get(versions[line], probe, recordNode)
        canvas.save()
        canvas.translate(0f, top.toFloat())
        canvas.drawRenderNode(node)
        canvas.restore()
    }

    // One probe key and one recording function for every line of every frame: the draw path allocates nothing.
    private val probe = LineDrawKey(Unit, Unit, 0, 0f, 0)
    private var recordResult: LineResult? = null
    private var recordFrame: FrameMetrics? = null
    private val recordNode: (RenderNode?) -> RenderNode = { reused ->
        val node = reused ?: RenderNode("line")
        record(node, checkNotNull(recordResult), checkNotNull(recordFrame))
        node
    }

    /** Records every row of the current line, in line-local coordinates. */
    private fun record(
        node: RenderNode,
        result: LineResult,
        frame: FrameMetrics,
    ) {
        val rowHeight = geometry.heights.rowHeight
        node.setPosition(0, 0, frame.width, row.layout.rowCount * rowHeight)
        val recording = node.beginRecording()
        try {
            row.canvas = recording
            for (index in 0 until row.layout.rowCount) {
                placeRow(index, 0L)
                drawRowText(result.tokens, frame)
            }
        } finally {
            node.endRecording()
        }
    }

    private fun drawRowText(
        tokens: LongArray,
        frame: FrameMetrics,
    ) {
        val layout = row.layout
        val bidi = layout.bidi
        if (bidi != null && row.index < bidi.rowCount) {
            drawBidiRowText(tokens, frame)
            return
        }
        var from = layout.rowStart(row.index)
        var to = layout.rowEnd(row.index)
        if (!geometry.wrap) {
            // Only the columns on screen are drawn, even inside a 2,000-char window.
            from = (layout.columnAt(row.index, frame.scrollX.toFloat()) - 1).coerceAtLeast(from)
            to = (layout.columnAt(row.index, (frame.scrollX + frame.width).toFloat()) + 1).coerceAtMost(to)
        }
        if (to <= from) return
        if (from >= layout.charsStart && to <= layout.charsStart + layout.chars.size) {
            row.chars = layout.chars
            row.charsStart = layout.charsStart
        } else {
            row.chars = fetch(row.lineStart, from, to)
            row.charsStart = from
        }
        row.baseline = row.top + frame.baseline
        RunPlanner.plan(tokens, from, to, row.chars, row.charsStart, runSink)
    }

    private val runSink =
        RunSink { start, end, kind, flags ->
            val style = styles.styleFor(kind, flags)
            val x = row.left + row.layout.xInRow(row.index, start)
            if (style.background != 0) {
                fill.color = style.background
                val inset = (row.bottom - row.top) * CHIP_INSET
                val right = row.left + row.layout.xInRow(row.index, end)
                row.canvas?.drawRect(x, row.top + inset, right, row.bottom - inset, fill)
            }
            drawRun(start, end, x, style)
        }

    private fun drawRun(
        start: Int,
        end: Int,
        x: Float,
        style: RunStyle,
    ) {
        val chars = row.chars
        var index = start - row.charsStart
        val endIndex = end - row.charsStart
        // A run cut at the screen edge may start on the second half of a pair.
        if (index in 1 until endIndex && chars[index].isLowSurrogate()) index++
        if (endIndex <= index) return
        applyStyle(style)
        val count = endIndex - index
        row.canvas?.drawTextRun(chars, index, count, index, count, x, row.baseline, false, textPaint)
    }

    private fun applyStyle(style: RunStyle) {
        textPaint.color = style.color
        textPaint.isFakeBoldText = style.bold
        textPaint.textSkewX = if (style.italic) ITALIC_SKEW else 0f
        textPaint.isStrikeThruText = style.strikethrough
    }

    /**
     * A row with right-to-left text ([LineRows.bidi]): every style run is
     * cut where the direction changes and each piece is drawn at its visual
     * x, right-to-left pieces with `isRtl` and their whole direction run as
     * shaping context, so Arabic joins across a bold boundary. The row is
     * drawn whole: it is never wider than the laid-out window.
     */
    private fun drawBidiRowText(
        tokens: LongArray,
        frame: FrameMetrics,
    ) {
        val layout = row.layout
        val from = maxOf(layout.rowStart(row.index), layout.charsStart)
        val to = minOf(layout.rowEnd(row.index), layout.charsStart + layout.chars.size)
        if (to <= from) return
        row.chars = layout.chars
        row.charsStart = layout.charsStart
        row.baseline = row.top + frame.baseline
        bidiRowsDrawn++
        RunPlanner.plan(tokens, from, to, row.chars, row.charsStart, bidiRunSink)
    }

    private val bidiRunSink =
        RunSink { start, end, kind, flags ->
            val bidi = checkNotNull(row.layout.bidi)
            val style = styles.styleFor(kind, flags)
            val line = row.index
            bidi.forEachPiece(line, start, end) { k, pieceStart, pieceEnd, left, right ->
                val x = row.left + left
                if (style.background != 0) {
                    fill.color = style.background
                    val inset = (row.bottom - row.top) * CHIP_INSET
                    row.canvas?.drawRect(x, row.top + inset, row.left + right, row.bottom - inset, fill)
                }
                applyStyle(style)
                val runStart = bidi.runStart(line, k)
                val runEnd = bidi.runEnd(line, k)
                val contextStart = runStart - row.charsStart
                row.canvas?.drawTextRun(
                    row.chars,
                    pieceStart - row.charsStart,
                    pieceEnd - pieceStart,
                    contextStart,
                    runEnd - runStart,
                    x,
                    row.baseline,
                    bidi.isRtl(line, k),
                    textPaint,
                )
            }
        }

    private fun fetch(
        lineStart: Int,
        from: Int,
        to: Int,
    ): CharArray {
        val count = to - from
        if (scratch.size < count) scratch = CharArray(count)
        session.document.text
            .getText(lineStart + from, lineStart + to)
            .toCharArray(scratch, 0, 0, count)
        return scratch
    }

    /**
     * Fills the part of `[start, end)` on the current row: the whole row, or
     * a bottom bar [thickness] px tall. With [tail], a selection running on
     * past the line's end shows the break as half a cell.
     */
    private fun highlightSpan(
        start: Int,
        end: Int,
        thickness: Float,
        tail: Boolean,
    ) {
        if (!spanOnRow(start, end)) return
        val top = if (thickness > 0f) row.bottom - thickness else row.top
        val extra = if (tail && spanRunsOn && thickness == 0f) halfCell else 0f
        val bidi = bidiRow()
        if (bidi != null) {
            // A logical range is one rectangle per direction run it crosses.
            bidi.forEachPiece(row.index, spanFrom, spanTo) { _, _, _, left, right ->
                row.canvas?.drawRect(row.left + left, top, row.left + right, row.bottom, fill)
            }
            val edge = row.left + bidi.rowRight(row.index)
            if (extra > 0f) row.canvas?.drawRect(edge, top, edge + extra, row.bottom, fill)
            return
        }
        row.canvas?.drawRect(spanLeft, top, spanRight + extra, row.bottom, fill)
    }

    /** Draws an outline [width] px wide around the part of `[start, end)` on the current row. */
    private fun outlineSpan(
        start: Int,
        end: Int,
        width: Float,
    ) {
        if (!spanOnRow(start, end)) return
        outline.color = styles.theme.findCurrentOutline
        outline.strokeWidth = width
        val half = width / 2
        val bidi = bidiRow()
        if (bidi != null) {
            bidi.forEachPiece(row.index, spanFrom, spanTo) { _, _, _, left, right ->
                row.canvas?.drawRect(
                    row.left + left + half,
                    row.top + half,
                    row.left + right - half,
                    row.bottom - half,
                    outline,
                )
            }
            return
        }
        row.canvas?.drawRect(spanLeft + half, row.top + half, spanRight - half, row.bottom - half, outline)
    }

    /** The current row's visual order, or null on the left-to-right fast path. */
    private fun bidiRow(): BidiText? = row.layout.bidi?.takeIf { row.index < it.rowCount }

    /** Half the baseline: how far a selection's line-break tail reaches past the line end. */
    private var halfCell = 0f
    private var spanLeft = 0f
    private var spanRight = 0f
    private var spanRunsOn = false
    private var spanFrom = 0
    private var spanTo = 0

    /** Sets [spanLeft] and [spanRight] for `[start, end)` on the current row; false when it is not on the row. */
    private fun spanOnRow(
        start: Int,
        end: Int,
    ): Boolean {
        val layout = row.layout
        val rowStart = row.lineStart + layout.rowStart(row.index)
        val rowEnd = row.lineStart + layout.rowEnd(row.index)
        // A span ending exactly where a wrapped row starts belongs to the row above.
        val misses = end < rowStart || start > rowEnd
        if (misses || (end == rowStart && row.index > 0)) return false
        val from = maxOf(start, rowStart) - row.lineStart
        val to = minOf(end, rowEnd) - row.lineStart
        spanFrom = from
        spanTo = to
        spanLeft = row.left + layout.xInRow(row.index, from)
        spanRight = row.left + layout.xInRow(row.index, to)
        spanRunsOn = end > rowEnd && row.index == layout.rowCount - 1
        return true
    }

    private fun drawCaret(
        canvas: Canvas,
        frame: FrameMetrics,
    ) {
        if (!frame.caretVisible || !frame.focused || session.hasSelection) return
        val point = geometry.locate(session.caret)
        val left = frame.textLeft - (if (geometry.wrap) 0 else frame.scrollX).toFloat()
        val x = left + point.x
        val top = (point.top - frame.scrollY).toFloat()
        fill.color = styles.theme.caret
        canvas.drawRect(x, top, x + CARET_WIDTH_DP * frame.density, top + geometry.heights.rowHeight, fill)
    }

    private companion object {
        const val SCRATCH_CHARS = 256
        const val CARET_WIDTH_DP = 2f
        const val UNDERLINE_DP = 1.5f
        const val OUTLINE_DP = 1.5f

        /** Lines taller than this many rows draw directly rather than into a display list. */
        const val MAX_CACHED_ROWS = 64

        /** Display lists kept: a few screens of lines. */
        const val NODE_CAPACITY = 256
        const val ITALIC_SKEW = -0.2f

        /** Inline-code chips leave this fraction of the row clear above and below. */
        const val CHIP_INSET = 0.12f
    }
}
