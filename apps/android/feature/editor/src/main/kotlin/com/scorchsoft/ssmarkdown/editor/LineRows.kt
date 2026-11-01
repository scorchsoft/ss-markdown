package com.scorchsoft.ssmarkdown.editor

/**
 * One laid-out line as the view draws and hit-tests it: one or more
 * visual rows, each a column range of the line. A [LineLayout] (No wrap)
 * is always a single row; a [WrapLayout] has as many as the width needs.
 *
 * Columns are UTF-16 chars from the line's start. Only the chars in
 * `[charsStart, charsStart + chars.size)` are held; outside that window a
 * layout estimates positions in monospace cells and the renderer reads the
 * few chars it needs from the tree (robustness.md, "Long lines are chunked").
 */
interface LineRows {
    /** Length of the whole line in chars, without its break. */
    val lineLength: Int

    /** Visual rows; at least 1. */
    val rowCount: Int

    /** Column of [chars]`[0]`. */
    val charsStart: Int

    /** The chars this layout measured. */
    val chars: CharArray

    /** First column of [row]. */
    fun rowStart(row: Int): Int

    /** One past the last column of [row]; the last row ends at [lineLength]. */
    fun rowEnd(row: Int): Int

    /** The row [column] is drawn on: a column on a wrap boundary starts the next row. */
    fun rowOf(column: Int): Int

    /** The x of the boundary before [column], relative to the left edge of its row. */
    fun xOf(column: Int): Float = xInRow(rowOf(column), column)

    /**
     * The x of the boundary before [column] measured from the left of
     * [row], even when [column] is the row's end (which [xOf] would put at
     * the start of the next row).
     */
    fun xInRow(
        row: Int,
        column: Int,
    ): Float

    /** The column nearest [x] on [row]; never between the halves of a surrogate pair. */
    fun columnAt(
        row: Int,
        x: Float,
    ): Int

    /** The widest row, in px; drives horizontal scrolling in No-wrap mode. */
    val width: Float
}
