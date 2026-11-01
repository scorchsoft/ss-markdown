/*
 * Ported in part from Visual Studio Code, pieceTreeBase.ts (`Piece`,
 * `getAccumulatedValue`, `positionInBuffer`). Copyright (c) Microsoft
 * Corporation, MIT License; see core/text/NOTICE.
 */
package com.scorchsoft.ssmarkdown.text

/**
 * An immutable run of text: `buffer.chars[start, end)`.
 *
 * [startLine] and [endLine] are the buffer lines containing [start] and
 * [end], so the line feeds inside the piece are just their difference.
 * [lineStarts] is the buffer's array *as it was when this piece was made*;
 * every entry this piece reads was already final then, so a piece can be
 * handed to another thread inside a [Snapshot] and read without locks.
 */
internal class Piece(
    val buffer: TextBuffer,
    val lineStarts: IntArray,
    val start: Int,
    val end: Int,
    val startLine: Int,
    val endLine: Int,
) {
    val length: Int get() = end - start

    val lineFeeds: Int get() = endLine - startLine

    fun charAt(index: Int): Char = buffer.chars[start + index]

    /**
     * Piece-relative offset just past the [count]th line feed in this piece:
     * 0 when [count] ≤ 0, [length] when the piece has fewer line feeds.
     * VS Code calls this `getAccumulatedValue(node, count - 1)`.
     */
    fun offsetAfterLineFeed(count: Int): Int =
        when {
            count <= 0 -> 0
            startLine + count > endLine -> length
            else -> lineStarts[startLine + count] - start
        }

    /** Line feeds in `[0, offset)` of this piece. */
    fun lineFeedsBefore(offset: Int): Int = lineOf(start + offset) - startLine

    /** The sub-piece `[from, to)`, both piece-relative. */
    fun slice(
        from: Int,
        to: Int,
    ): Piece =
        if (from == 0 && to == length) {
            this
        } else {
            Piece(buffer, lineStarts, start + from, start + to, lineOf(start + from), lineOf(start + to))
        }

    /** The buffer line containing [bufferOffset]; a binary search within this piece's lines. */
    private fun lineOf(bufferOffset: Int): Int {
        var low = startLine
        var high = endLine
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= bufferOffset) low = mid else high = mid - 1
        }
        return low
    }

    companion object {
        /** The sentinel's piece: no text, no lines. */
        val EMPTY: Piece = Piece(TextBuffer.EMPTY, TextBuffer.EMPTY.lineStarts, 0, 0, 0, 0)

        /** The whole of a sealed chunk. */
        fun of(buffer: TextBuffer): Piece = Piece(buffer, buffer.lineStarts, 0, buffer.length, 0, buffer.lineCount - 1)
    }
}
