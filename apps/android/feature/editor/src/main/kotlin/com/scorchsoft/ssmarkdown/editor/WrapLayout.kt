package com.scorchsoft.ssmarkdown.editor

/**
 * A line broken into rows no wider than the text column (Wrap mode).
 *
 * The first [measuredLength] chars (at most [WRAP_WINDOW], robustness.md:
 * "10,000 when wrapped") are measured and broken greedily at the last
 * space or tab that fits, or mid-word when a word is wider than the row.
 * Anything past that is split into estimated rows of [estimatedColumns]
 * monospace cells, so a 5 MB line costs one window of measurement and an
 * honest row count rather than a freeze.
 *
 * Immutable once built; built by [LineMeasurer.measureWrapped].
 */
@Suppress("LongParameterList") // One immutable value built in one place; a holder would only rename the fields.
class WrapLayout internal constructor(
    override val lineLength: Int,
    override val chars: CharArray,
    /** `positions[i]` is the x of the boundary before column i, from the line start, unwrapped. */
    private val positions: FloatArray,
    /** First column of every measured row. */
    private val rowStarts: IntArray,
    private val charWidth: Float,
    /** The width rows were broken to, in px. */
    override val width: Float,
    /** The measured rows' visual order when the line holds right-to-left text ([BidiText]); null otherwise. */
    override val bidi: BidiText? = null,
) : LineRows {
    /** Columns per estimated row past the measured window: whole monospace cells that fit the width. */
    val estimatedColumns: Int = (width / charWidth.coerceAtLeast(MIN_CELL)).toInt().coerceAtLeast(1)

    /** Chars measured; columns past this are estimated. */
    val measuredLength: Int get() = chars.size

    private val measuredRows: Int get() = rowStarts.size

    override val charsStart: Int get() = 0

    override val rowCount: Int =
        rowStarts.size + ((lineLength - chars.size + estimatedColumns - 1) / estimatedColumns).coerceAtLeast(0)

    override fun rowStart(row: Int): Int =
        if (row < measuredRows) rowStarts[row] else measuredLength + (row - measuredRows) * estimatedColumns

    override fun rowEnd(row: Int): Int = if (row + 1 < rowCount) rowStart(row + 1) else lineLength

    override fun rowOf(column: Int): Int {
        if (column >= measuredLength && lineLength > measuredLength) {
            return (measuredRows + (column - measuredLength) / estimatedColumns).coerceAtMost(rowCount - 1)
        }
        var low = 0
        var high = measuredRows - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (rowStarts[mid] <= column) low = mid else high = mid - 1
        }
        return low
    }

    override fun xInRow(
        row: Int,
        column: Int,
    ): Float {
        val start = rowStart(row)
        return when {
            row >= measuredRows -> (column - start) * charWidth
            bidi != null -> bidi.caretX(row, column)
            else -> positions[column] - positions[start]
        }
    }

    override fun columnAt(
        row: Int,
        x: Float,
    ): Int {
        val start = rowStart(row)
        val end = rowEnd(row)
        // Past the end of a wrapped row the caret stays on that row, before the char the row broke at.
        val last = if (row == rowCount - 1) end else (end - 1).coerceAtLeast(start)
        if (bidi != null && row < measuredRows) return bidi.columnAt(row, x, last)
        val column =
            if (row < measuredRows) {
                nearest(start, end, positions[start] + x)
            } else {
                start + if (charWidth <= 0f) 0 else Math.round(x / charWidth)
            }
        return snap(column.coerceIn(start, last))
    }

    private fun nearest(
        start: Int,
        end: Int,
        x: Float,
    ): Int {
        var low = start
        var high = end
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (positions[mid] <= x) low = mid else high = mid
        }
        return if (x - positions[low] <= positions[high] - x) low else high
    }

    private fun snap(column: Int): Int {
        val splits =
            column in 1 until measuredLength && chars[column].isLowSurrogate() && chars[column - 1].isHighSurrogate()
        return if (splits) column - 1 else column
    }

    /** Limits and the line breaker. */
    companion object {
        /** Chars measured per wrapped line. */
        const val WRAP_WINDOW: Int = 10_000

        private const val MIN_CELL = 1f

        /**
         * Greedy line breaking over measured [positions] (`chars.size + 1`
         * boundaries) for rows at most [maxWidth] px wide. Every row holds
         * at least one char (one grapheme half is never stranded), so a
         * glyph wider than the row still makes progress.
         */
        internal fun breakRows(
            chars: CharArray,
            positions: FloatArray,
            maxWidth: Float,
        ): IntArray {
            val starts = ArrayList<Int>()
            starts += 0
            var rowStart = 0
            var lastBreak = -1
            var i = 0
            while (i < chars.size) {
                val overflows = positions[i + 1] - positions[rowStart] > maxWidth && i > rowStart
                if (overflows && !chars[i].isWhitespace()) {
                    var at = if (lastBreak > rowStart) lastBreak else i
                    if (chars[at].isLowSurrogate() && at - 1 > rowStart) at--
                    starts += at
                    rowStart = at
                    lastBreak = -1
                }
                if (chars[i] == ' ' || chars[i] == '\t') lastBreak = i + 1
                i++
            }
            return starts.toIntArray()
        }
    }
}
