package com.scorchsoft.ssmarkdown.editor

/**
 * Left and Right arrow movement in **visual** order on lines that mix
 * directions (docs/edge-cases.md, "Right-to-left and mixed-direction
 * text"): inside Hebrew or Arabic, Left moves on through the word, as it
 * does in every bidi-aware editor. Lines with no right-to-left text, which
 * are nearly all of them, never reach the `Bidi` algorithm.
 *
 * The line is analysed by [BidiText] at a left-to-right paragraph level (a
 * code editor's lines are left-aligned). Caret positions are the
 * boundaries between chars, placed where [BidiText.caretX] draws them, and
 * the arrow moves to the neighbour in that visual order. Surrogate pairs
 * and the caller's grapheme boundaries are respected by `isBoundary`.
 *
 * The order is the whole line's, which is exactly how a No-wrap line and
 * any one-row line are drawn. A wrapped line reorders each row on its own,
 * so on a mixed line that wraps the arrows can cross a row in logical
 * rather than visual order there; Up, Down and taps use the row's layout.
 */
internal object BidiCaret {
    /** Lines longer than this move logically: bidi on a 5 MB minified line would stall a key press. */
    const val MAX_ANALYSED_CHARS: Int = 10_000

    /** True when [line] has any right-to-left char and so needs visual movement. */
    fun needsBidi(line: CharSequence): Boolean {
        if (line.length > MAX_ANALYSED_CHARS) return false
        return BidiText.paragraph(line.toString().toCharArray()) != null
    }

    /**
     * The column the caret reaches from [column] with one press of Right
     * ([right]) or Left on [line]. Returns [column] at the visual edge.
     */
    fun move(
        line: CharSequence,
        column: Int,
        right: Boolean,
        isBoundary: (Int) -> Boolean = { true },
    ): Int {
        val order = visualOrder(line, isBoundary)
        val here = order.indexOf(column).takeIf { it >= 0 } ?: return column
        val next = if (right) here + 1 else here - 1
        return order.getOrElse(next) { column }
    }

    /**
     * Every caret position of [line], left to right as drawn: the order of
     * [BidiText.caretX] over the whole line, so the arrows walk exactly the
     * order the renderer draws a one-row line in. Every char counts as one
     * cell here; only the order matters.
     */
    fun visualOrder(
        line: CharSequence,
        isBoundary: (Int) -> Boolean = { true },
    ): List<Int> {
        val chars = line.toString().toCharArray()
        val text = BidiText.of(chars, FloatArray(chars.size + 1) { it.toFloat() })
        return (0..chars.size)
            .filter { it == 0 || it == chars.size || (!chars[it].isLowSurrogate() && isBoundary(it)) }
            .sortedWith(compareBy({ text?.caretX(0, it) ?: it.toFloat() }, { it }))
    }
}
