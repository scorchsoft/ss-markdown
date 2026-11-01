package com.scorchsoft.ssmarkdown.editor

import android.graphics.Paint
import com.scorchsoft.ssmarkdown.text.PieceTree

/**
 * Turns a window of one line into a [LineLayout] with a single
 * `Paint.getTextRunAdvances` call: the unwrapped path from robustness.md
 * ("Unwrapped lines use getRunAdvance + drawTextRun"). Wrapped lines,
 * which need `MeasuredText` + `LineBreaker` off the main thread, are a
 * later iteration.
 *
 * Reads only `[lineStart + start, lineStart + end)` from the tree, so a
 * 5 MB single line costs the same as a 2,000-char one.
 */
internal class LineMeasurer(
    private val paint: Paint,
) {
    /** Width of one monospace cell; the estimate for unmeasured columns. */
    var charWidth: Float = paint.measureText(CELL)
        private set

    /** Call after changing the paint's size or typeface. */
    fun onPaintChanged() {
        charWidth = paint.measureText(CELL)
    }

    /** Lays out [line] of [text] for a window requested to start at [windowStart]. */
    fun measure(
        text: PieceTree,
        line: Int,
        windowStart: Int,
    ): LineLayout {
        val lineStart = text.lineStartOffset(line)
        val length = text.lineEndOffset(line) - lineStart
        var start = windowStart.coerceIn(0, length)
        var end = if (length <= LineWindow.SIZE) length else minOf(length, start + LineWindow.SIZE)
        // Never cut a surrogate pair at either edge: widen by one instead.
        if (start in 1 until length && text[lineStart + start].isLowSurrogate()) start--
        if (end in 1 until length && text[lineStart + end].isLowSurrogate()) end++
        val chars = text.getText(lineStart + start, lineStart + end).toCharArray()
        return LineLayout(length, start, chars, positionsOf(chars, start), charWidth)
    }

    /** Prefix sums of the advances, starting at the window's estimated x. Tabs are [TAB_CELLS] cells wide. */
    private fun positionsOf(
        chars: CharArray,
        windowStart: Int,
    ): FloatArray {
        val count = chars.size
        val advances = FloatArray(count)
        if (count > 0) paint.getTextRunAdvances(chars, 0, count, 0, count, false, advances, 0)
        val positions = FloatArray(count + 1)
        positions[0] = windowStart * charWidth
        for (i in 0 until count) {
            val advance = if (chars[i] == '\t') TAB_CELLS * charWidth else advances[i]
            positions[i + 1] = positions[i] + advance
        }
        return positions
    }

    companion object {
        /** A tab is drawn as this many cells (no tab stops yet: a spike limit). */
        const val TAB_CELLS: Int = 4

        private const val CELL = "M"
    }
}
