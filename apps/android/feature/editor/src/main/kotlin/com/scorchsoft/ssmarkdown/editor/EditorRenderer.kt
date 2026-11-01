package com.scorchsoft.ssmarkdown.editor

import android.graphics.Canvas
import android.graphics.Paint
import com.scorchsoft.ssmarkdown.markdown.LineKind

/**
 * Draws the visible rows: code-block bands, selection, syntax-coloured
 * text runs, the composing underline, the caret and the gutter. Holds the
 * paints; everything it draws comes from the [EditorGeometry], the
 * [EditingSession] and the [HighlightScheduler], so it keeps no state of
 * its own between frames.
 */
internal class EditorRenderer(
    private val geometry: EditorGeometry,
    private val session: EditingSession,
    private val highlight: HighlightScheduler,
    /** The text paint the measurer uses; runs restyle a copy so measurement never changes. */
    basePaint: Paint,
) {
    /** The colours and the styles built from them. */
    var styles: SyntaxStyles = SyntaxStyles(EditorTheme.LIGHT)

    private val textPaint = Paint(basePaint)
    private val gutterPaint = Paint(basePaint).apply { textAlign = Paint.Align.RIGHT }
    private val fill = Paint()
    private val lineNumber = CharArray(MAX_LINE_NUMBER_DIGITS)
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
        gutterPaint.set(basePaint)
        gutterPaint.textAlign = Paint.Align.RIGHT
    }

    /** Draws [visible] lines; the canvas is in view coordinates (scroll already undone). */
    fun draw(
        canvas: Canvas,
        visible: IntRange,
        frame: FrameMetrics,
    ) {
        val theme = styles.theme
        canvas.drawColor(theme.background)
        canvas.save()
        canvas.clipRect(frame.gutterWidth, 0, frame.width, frame.height)
        for (line in visible) drawLine(canvas, line, frame)
        drawCaret(canvas, frame)
        canvas.restore()
        drawGutter(canvas, visible, frame)
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
            row.index = index
            row.top = (top + index.toLong() * rowHeight).toFloat()
            row.bottom = row.top + rowHeight
            if (session.hasSelection) {
                fill.color = styles.theme.selection
                highlightSpan(session.selectionStart, session.selectionEnd, 0f, frame)
            }
            drawRowText(result.tokens, frame)
            if (session.isComposing) {
                fill.color = styles.theme.composingUnderline
                highlightSpan(session.composingStart, session.composingEnd, UNDERLINE_DP * frame.density, frame)
            }
        }
    }

    private fun drawRowText(
        tokens: LongArray,
        frame: FrameMetrics,
    ) {
        val layout = row.layout
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
        textPaint.color = style.color
        textPaint.isFakeBoldText = style.bold
        textPaint.textSkewX = if (style.italic) ITALIC_SKEW else 0f
        textPaint.isStrikeThruText = style.strikethrough
        val count = endIndex - index
        row.canvas?.drawTextRun(chars, index, count, index, count, x, row.baseline, false, textPaint)
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

    /** Fills the part of `[start, end)` on the current row: the whole row, or a bottom bar [thickness] px tall. */
    private fun highlightSpan(
        start: Int,
        end: Int,
        thickness: Float,
        frame: FrameMetrics,
    ) {
        val layout = row.layout
        val rowStart = row.lineStart + layout.rowStart(row.index)
        val rowEnd = row.lineStart + layout.rowEnd(row.index)
        // A span ending exactly where a wrapped row starts belongs to the row above.
        val misses = end < rowStart || start > rowEnd
        if (misses || (end == rowStart && row.index > 0)) return
        val from = maxOf(start, rowStart) - row.lineStart
        val to = minOf(end, rowEnd) - row.lineStart
        // A selection running on past the line's end shows the break as half a cell.
        val lastRow = row.index == layout.rowCount - 1
        val tail = if (thickness == 0f && end > rowEnd && lastRow) frame.baseline / 2 else 0f
        val top = if (thickness > 0f) row.bottom - thickness else row.top
        val left = row.left + layout.xInRow(row.index, from)
        row.canvas?.drawRect(left, top, row.left + layout.xInRow(row.index, to) + tail, row.bottom, fill)
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

    private fun drawGutter(
        canvas: Canvas,
        visible: IntRange,
        frame: FrameMetrics,
    ) {
        fill.color = styles.theme.gutterBackground
        canvas.drawRect(0f, 0f, frame.gutterWidth.toFloat(), frame.height.toFloat(), fill)
        gutterPaint.color = styles.theme.gutterText
        val right = (frame.gutterWidth - frame.gap).toFloat()
        for (line in visible) {
            val start = writeLineNumber(line + 1)
            val y = (geometry.heights.lineTop(line) - frame.scrollY) + frame.baseline
            canvas.drawText(lineNumber, start, lineNumber.size - start, right, y, gutterPaint)
        }
    }

    /** Writes [number] right-aligned into [lineNumber] (no String per frame); returns its first index. */
    private fun writeLineNumber(number: Int): Int {
        var n = number
        var index = lineNumber.size
        do {
            lineNumber[--index] = '0' + n % DECIMAL
            n /= DECIMAL
        } while (n > 0)
        return index
    }

    private companion object {
        const val MAX_LINE_NUMBER_DIGITS = 10
        const val DECIMAL = 10
        const val SCRATCH_CHARS = 256
        const val CARET_WIDTH_DP = 2f
        const val UNDERLINE_DP = 1.5f
        const val ITALIC_SKEW = -0.2f

        /** Inline-code chips leave this fraction of the row clear above and below. */
        const val CHIP_INSET = 0.12f
    }
}
