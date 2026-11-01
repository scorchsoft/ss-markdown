package com.scorchsoft.ssmarkdown.editor

/**
 * The laid-out part of one line: the chars of a window `[windowStart,
 * windowStart + count)` and the x position of every boundary in it.
 *
 * Long lines are never laid out whole (docs/robustness.md, "Long lines are
 * chunked"; CodeMirror's rule). Only about [LineWindow.SIZE] chars around
 * the viewport are measured; positions outside the window are *estimated*
 * as `column × charWidth`, which is exact for the monospace ASCII that
 * makes up nearly every huge line (base64, minified JSON). Inside the
 * window, positions are measured, so CJK and emoji line up with the caret.
 *
 * Immutable once built, so the cache can hand the same instance to every
 * frame without copying.
 */
class LineLayout(
    /** Length of the whole line in chars, without its break. */
    val lineLength: Int,
    /** Column of [chars]`[0]` within the line. */
    val windowStart: Int,
    /** The window's chars, tabs included (they are drawn as gaps). */
    val chars: CharArray,
    /**
     * `positions[i]` is the x of the boundary before `chars[i]`, relative to
     * the line's left edge; `positions[count]` is the window's right edge.
     */
    private val positions: FloatArray,
    /** The estimate used outside the window: one monospace cell. */
    private val charWidth: Float,
) {
    /** Chars in the window. */
    val count: Int get() = chars.size

    /** One past the window's last column. */
    val windowEnd: Int get() = windowStart + count

    /** True when the whole line is in the window, so [width] is measured rather than estimated. */
    val isComplete: Boolean get() = windowStart == 0 && count == lineLength

    /** The line's width in px: measured when complete, else the window plus estimates either side. */
    val width: Float get() = xOf(lineLength)

    init {
        require(positions.size == chars.size + 1) { "need one position per boundary" }
    }

    /** The x of the boundary before [column] (0..[lineLength]). */
    fun xOf(column: Int): Float =
        when {
            column <= windowStart -> column * charWidth
            column >= windowEnd -> positions[count] + (column - windowEnd) * charWidth
            else -> positions[column - windowStart]
        }

    /**
     * The column whose boundary is nearest to [x], never between the two
     * halves of a surrogate pair. A tap past the end of the line lands at
     * its end, as in every text editor.
     */
    fun columnAt(x: Float): Int {
        val left = positions[0]
        val right = positions[count]
        return when {
            x <= left -> estimateColumn(x).coerceIn(0, windowStart)
            x >= right -> (windowEnd + estimateColumn(x - right)).coerceIn(windowEnd, lineLength)
            else -> snapToCharBoundary(windowStart + nearestBoundary(x))
        }
    }

    /** Binary search for the window boundary nearest [x], which lies inside the window. */
    private fun nearestBoundary(x: Float): Int {
        var low = 0
        var high = count
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (positions[mid] <= x) low = mid else high = mid
        }
        return if (x - positions[low] <= positions[high] - x) low else high
    }

    private fun estimateColumn(x: Float): Int = if (charWidth <= 0f) 0 else Math.round(x / charWidth)

    private fun snapToCharBoundary(column: Int): Int {
        val index = column - windowStart
        val splitsPair = index in 1 until count && chars[index].isLowSurrogate() && chars[index - 1].isHighSurrogate()
        return if (splitsPair) column - 1 else column
    }
}

/**
 * Chooses which part of a line to lay out. A line no longer than [SIZE]
 * is laid out whole. A longer one gets a [SIZE]-char window around the
 * viewport, whose start snaps to [SNAP] columns so that a small horizontal
 * scroll reuses the cached layout instead of re-measuring every frame.
 */
object LineWindow {
    /** Chars laid out per long line (CodeMirror uses 2,000 unwrapped). */
    const val SIZE: Int = 2_000

    /** Window starts are multiples of this, so they move in steps. */
    const val SNAP: Int = 500

    /**
     * The window's first column for a line of [lineLength] chars when the
     * viewport's centre is at [centreColumn]. The viewport must be narrower
     * than [SNAP] columns either side of the centre to stay inside the
     * window, which holds for any phone (about 50–200 columns).
     */
    fun startFor(
        lineLength: Int,
        centreColumn: Int,
    ): Int {
        if (lineLength <= SIZE) return 0
        val snapped = ((centreColumn - SIZE / 2).coerceAtLeast(0) / SNAP) * SNAP
        return snapped.coerceAtMost(lineLength - SIZE)
    }
}
