package com.scorchsoft.ssmarkdown.editor

import java.text.Bidi

/**
 * Where the chars of a line holding right-to-left text are drawn: the
 * visual order of its runs, row by row (docs/edge-cases.md, "Right-to-left
 * and mixed-direction text").
 *
 * Built once per laid-out line by [LineMeasurer], and only for lines that
 * have a right-to-left char; every other line has no [BidiText] at all, so
 * the left-to-right draw path never reaches `java.text.Bidi` and allocates
 * nothing new ([paragraph] answers null after one scan of the chars).
 *
 * The paragraph level is left to right, because a code editor's lines are
 * left-aligned. Each row of a wrapped line is reordered on its own
 * (UAX #9 rules L1–L2 apply per line, after breaking), using the levels the
 * whole paragraph resolved.
 *
 * Columns are line columns; x values are in the owning layout's row
 * coordinates (the [origin] of the first char's row, then advances).
 *
 * ### One caret x per column
 *
 * Where two runs meet, a caret position touches two visual places. The
 * side with the higher embedding level owns it: in `ab שלום cd` the caret
 * before `ש` sits at the right of the Hebrew word (where a Hebrew letter
 * typed there appears) and the caret after `ם` at its left. Every visual
 * boundary of a simple mixed line then has exactly one column, so a tap and
 * the caret it places agree, and [BidiCaret] walks the same order.
 */
class BidiText private constructor(
    private val chars: CharArray,
    private val charsStart: Int,
    private val origin: Float,
    private val glyphs: Glyphs,
    /** Window index of every analysed row's start, plus the end of the last. */
    private val rowBounds: IntArray,
    private val runs: Runs,
) {
    /** Per char of the window: resolved level, visual left from its row's left, and advance. */
    private class Glyphs(
        count: Int,
        val advance: FloatArray,
    ) {
        val levels = ByteArray(count)
        val left = FloatArray(count)
    }

    /** Every row's runs in visual order, flat: row r owns `[rowFirst[r], rowFirst[r + 1])`. */
    private class Runs(
        val rowFirst: IntArray,
        val starts: IntArray,
        val ends: IntArray,
        val rtl: BooleanArray,
    )

    private val levels get() = glyphs.levels
    private val left get() = glyphs.left
    private val advance get() = glyphs.advance

    /** Rows analysed; a wrapped line's estimated rows past its measured window have none. */
    val rowCount: Int get() = rowBounds.size - 1

    /** Runs on [row], in visual order, left to right. */
    fun runCount(row: Int): Int = runs.rowFirst[row + 1] - runs.rowFirst[row]

    /** First column of run [k] of [row]. */
    fun runStart(
        row: Int,
        k: Int,
    ): Int = charsStart + runs.starts[runs.rowFirst[row] + k]

    /** One past the last column of run [k] of [row]. */
    fun runEnd(
        row: Int,
        k: Int,
    ): Int = charsStart + runs.ends[runs.rowFirst[row] + k]

    /** True when run [k] of [row] is drawn right to left. */
    fun isRtl(
        row: Int,
        k: Int,
    ): Boolean = runs.rtl[runs.rowFirst[row] + k]

    /** The left edge of the char at [column] as drawn. */
    fun charLeft(column: Int): Float = origin + left[column - charsStart]

    /** The right edge of [row] as drawn: where a selection's line-break tail starts. */
    fun rowRight(row: Int): Float {
        var width = 0f
        for (i in rowBounds[row] until rowBounds[row + 1]) width += advance[i]
        return origin + width
    }

    /** The caret x of [column] on [row]; see "One caret x per column". */
    fun caretX(
        row: Int,
        column: Int,
    ): Float {
        val a = rowBounds[row]
        val b = rowBounds[row + 1]
        val c = (column - charsStart).coerceIn(a, b)
        val before = if (c > a) levels[c - 1] else PARAGRAPH_LEVEL
        val after = if (c < b) levels[c] else PARAGRAPH_LEVEL
        val x =
            when {
                // The char after owns it: its leading edge (left in LTR, right in RTL).
                c < b && after >= before -> if (after.isOdd()) left[c] + advance[c] else left[c]

                // The char before owns it: its trailing edge.
                c > a -> if (before.isOdd()) left[c - 1] else left[c - 1] + advance[c - 1]

                else -> 0f
            }
        return origin + x
    }

    /**
     * The column on [row] whose caret is nearest [x], at most [lastColumn],
     * never between the halves of a surrogate pair. Ties go to the earlier
     * column. A scan of the row: bidi rows are short and rare.
     */
    fun columnAt(
        row: Int,
        x: Float,
        lastColumn: Int,
    ): Int {
        val a = rowBounds[row]
        val b = minOf(rowBounds[row + 1], lastColumn - charsStart).coerceAtLeast(a)
        var best = a
        var bestDistance = Float.MAX_VALUE
        for (c in a..b) {
            if (c in (a + 1) until rowBounds[row + 1] && chars[c].isLowSurrogate() && chars[c - 1].isHighSurrogate()) {
                continue
            }
            val distance = kotlin.math.abs(caretX(row, charsStart + c) - x)
            if (distance < bestDistance) {
                best = c
                bestDistance = distance
            }
        }
        return charsStart + best
    }

    /** The advance of the char at [column]. */
    fun advanceOf(column: Int): Float = advance[column - charsStart]

    /**
     * Calls [action] for every visual piece of the columns `[from, to)` on
     * [row], left to right: the run [k] it lies in, its columns
     * `[start, end)` and its [left] and [right] x. A logical range crosses
     * runs, so one selection or one style run can be several pieces on screen.
     */
    inline fun forEachPiece(
        row: Int,
        from: Int,
        to: Int,
        action: (k: Int, start: Int, end: Int, left: Float, right: Float) -> Unit,
    ) {
        for (k in 0 until runCount(row)) {
            val start = maxOf(from, runStart(row, k))
            val end = minOf(to, runEnd(row, k))
            if (end <= start) continue
            // A right-to-left piece starts (in memory) at its right edge.
            val leftmost = if (isRtl(row, k)) end - 1 else start
            val rightmost = if (isRtl(row, k)) start else end - 1
            action(k, start, end, charLeft(leftmost), charLeft(rightmost) + advanceOf(rightmost))
        }
    }

    /** Builds [BidiText] for lines that need it. */
    companion object {
        /** The paragraph level: left to right, like every line of a code editor. */
        private const val PARAGRAPH_LEVEL: Byte = 0

        /** The first char that can be right to left (Hebrew); below it no scan of `Bidi` is needed. */
        private const val FIRST_RTL_CHAR = '֐'

        /**
         * The bidi analysis of [chars], or null when nothing in them is right
         * to left: the fast path for nearly every line, decided by one pass
         * over the chars with no allocation.
         */
        fun paragraph(chars: CharArray): Bidi? {
            var candidate = false
            for (c in chars) {
                if (c >= FIRST_RTL_CHAR) {
                    candidate = true
                    break
                }
            }
            if (!candidate || !Bidi.requiresBidi(chars, 0, chars.size)) return null
            return Bidi(chars, 0, null, 0, chars.size, Bidi.DIRECTION_LEFT_TO_RIGHT)
        }

        /**
         * Lays out [chars] (a line's window from column [charsStart]) in
         * visual order. [positions] are the logical x of every boundary
         * (`chars.size + 1`, measured per direction run); [rowStarts] are the
         * window indexes where rows start, the first being 0. Returns null
         * for a line with no right-to-left text.
         */
        fun of(
            chars: CharArray,
            positions: FloatArray,
            rowStarts: IntArray = intArrayOf(0),
            charsStart: Int = 0,
            origin: Float = positions[0],
        ): BidiText? = paragraph(chars)?.let { layOut(it, chars, positions, rowStarts, charsStart, origin) }

        /** [of] for a [paragraph] already analysed (the measurer needs it for the advances first). */
        @Suppress("LongParameterList") // Plain values from the measurer; built once per bidi line.
        fun layOut(
            paragraph: Bidi,
            chars: CharArray,
            positions: FloatArray,
            rowStarts: IntArray,
            charsStart: Int,
            origin: Float,
        ): BidiText {
            val count = chars.size
            val glyphs = Glyphs(count, FloatArray(count) { positions[it + 1] - positions[it] })
            val bounds = rowStarts.copyOf(rowStarts.size + 1)
            bounds[rowStarts.size] = count
            val rowFirst = IntArray(rowStarts.size + 1)
            val runs = RunList()
            for (row in rowStarts.indices) {
                rowFirst[row] = runs.size
                if (bounds[row + 1] > bounds[row]) layOutRow(paragraph, bounds[row], bounds[row + 1], glyphs, runs)
            }
            rowFirst[rowStarts.size] = runs.size
            val flat = Runs(rowFirst, runs.starts.toIntArray(), runs.ends.toIntArray(), runs.rtl.toBooleanArray())
            return BidiText(chars, charsStart, origin, glyphs, bounds, flat)
        }

        private class RunList {
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val rtl = ArrayList<Boolean>()
            val size: Int get() = starts.size
        }

        /** Resolves levels for row `[a, b)`, orders its runs visually and places every char. */
        private fun layOutRow(
            paragraph: Bidi,
            a: Int,
            b: Int,
            glyphs: Glyphs,
            out: RunList,
        ) {
            // createLineBidi applies rule L1: trailing spaces fall back to the paragraph level.
            val line = paragraph.createLineBidi(a, b)
            for (i in a until b) glyphs.levels[i] = line.getLevelAt(i - a).toByte()
            val count = line.runCount
            val runLevels = ByteArray(count) { line.getRunLevel(it).toByte() }
            val order = Array<Any>(count) { it }
            Bidi.reorderVisually(runLevels, 0, order, 0, count)
            var x = 0f
            for (o in order) {
                val run = o as Int
                val start = a + line.getRunStart(run)
                val end = a + line.getRunLimit(run)
                val rtl = runLevels[run].isOdd()
                out.starts += start
                out.ends += end
                out.rtl += rtl
                // Inside a right-to-left run the last char in memory is drawn first.
                val indices = if (rtl) (end - 1 downTo start) else (start until end)
                for (i in indices) {
                    glyphs.left[i] = x
                    x += glyphs.advance[i]
                }
            }
        }
    }
}

/** True for a right-to-left embedding level. */
private fun Byte.isOdd(): Boolean = toInt() and 1 == 1
