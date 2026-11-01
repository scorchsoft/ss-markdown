package com.scorchsoft.ssmarkdown.editor

import java.text.Bidi

/**
 * Left and Right arrow movement in **visual** order on lines that mix
 * directions (docs/edge-cases.md, "Right-to-left and mixed-direction
 * text"): inside Hebrew or Arabic, Left moves on through the word, as it
 * does in every bidi-aware editor. Lines with no right-to-left text, which
 * are nearly all of them, never reach the `Bidi` algorithm.
 *
 * The line is analysed with `java.text.Bidi` at a left-to-right paragraph
 * level (a code editor's lines are left-aligned). Caret positions are the
 * boundaries between chars; each is placed at the visual edge of the char
 * after it (or of the line's end), and the arrow moves to the neighbour in
 * that visual order. Surrogate pairs and the caller's grapheme boundaries
 * are respected by [isBoundary].
 */
internal object BidiCaret {
    /** Lines longer than this move logically: bidi on a 5 MB minified line would stall a key press. */
    const val MAX_ANALYSED_CHARS: Int = 10_000

    /** True when [line] has any right-to-left char and so needs visual movement. */
    fun needsBidi(line: CharSequence): Boolean {
        if (line.length > MAX_ANALYSED_CHARS) return false
        val chars = line.toString().toCharArray()
        return Bidi.requiresBidi(chars, 0, chars.size)
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

    /** Every caret position of [line], left to right as drawn. */
    fun visualOrder(
        line: CharSequence,
        isBoundary: (Int) -> Boolean = { true },
    ): List<Int> {
        val text = line.toString()
        val bidi = Bidi(text, Bidi.DIRECTION_LEFT_TO_RIGHT)
        // Visual slot of every char: runs reordered by level, chars reversed inside odd-level runs.
        val runs = bidi.runCount
        val levels = ByteArray(runs) { bidi.getRunLevel(it).toByte() }
        val objects = Array<Any>(runs) { it }
        Bidi.reorderVisually(levels, 0, objects, 0, runs)
        val slot = IntArray(text.length)
        var x = 0
        for (o in objects) {
            val run = o as Int
            val start = bidi.getRunStart(run)
            val limit = bidi.getRunLimit(run)
            val rtl = bidi.getRunLevel(run) % 2 == 1
            for (k in 0 until limit - start) slot[if (rtl) limit - 1 - k else start + k] = x++
        }
        // A caret before char i sits at that char's leading edge: its left in LTR, its right in RTL;
        // edges are in half cells; where two carets share an edge, logical order breaks the tie.
        val edge = IntArray(text.length + 1)
        for (i in text.indices) edge[i] = 2 * slot[i] + if (bidi.getLevelAt(i) % 2 == 1) 2 else 0
        // The end of the line sits at its last char's trailing edge.
        val last = text.length - 1
        edge[text.length] =
            when {
                last < 0 -> 0
                bidi.getLevelAt(last) % 2 == 1 -> 2 * slot[last]
                else -> 2 * slot[last] + 2
            }
        return (0..text.length)
            .filter { it == 0 || it == text.length || (!text[it].isLowSurrogate() && isBoundary(it)) }
            .sortedWith(compareBy({ edge[it] }, { it }))
    }
}
