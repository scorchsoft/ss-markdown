package com.scorchsoft.ssmarkdown.text

/**
 * An immutable view of the text at one moment, safe to read on any thread
 * while the editor keeps typing (docs/robustness.md, "Snapshots").
 *
 * Taking one costs O(pieces), not O(chars): it copies the list of pieces,
 * and pieces point into buffers whose written chars never change. The
 * tokenizer, the save pipeline, history diffs and preview sync all read
 * from a snapshot rather than from the live [PieceTree].
 *
 * A snapshot is also how text moves *without copying*: [subSequence]
 * returns another snapshot, and inserting a snapshot into a [PieceTree]
 * links its pieces back in. That is what makes undoing the deletion of a
 * 100 MB selection as cheap as the deletion itself.
 *
 * Reading by index is O(log pieces), with a hint that makes sequential
 * reads O(1). The hint is the only mutable state: a plain int whose stale
 * value is merely a slower guess, so concurrent readers stay correct.
 */
@Suppress("TooManyFunctions") // CharSequence plus line access: a reader's whole API in one immutable type.
public class Snapshot internal constructor(
    internal val pieces: Array<Piece>,
) : CharSequence {
    private val pieceStarts = IntArray(pieces.size + 1)
    private val lineFeedsBeforePiece = IntArray(pieces.size + 1)
    private var hint = 0

    init {
        for (i in pieces.indices) {
            pieceStarts[i + 1] = pieceStarts[i] + pieces[i].length
            lineFeedsBeforePiece[i + 1] = lineFeedsBeforePiece[i] + pieces[i].lineFeeds
        }
    }

    /** Total chars, as in the live text when the snapshot was taken. */
    override val length: Int get() = pieceStarts[pieces.size]

    /** Lines are separated by `'\n'`, so this is always line feeds + 1 (an empty text has one line). */
    public val lineCount: Int get() = lineFeedsBeforePiece[pieces.size] + 1

    override fun get(index: Int): Char {
        if (index < 0 || index >= length) throw IndexOutOfBoundsException("index $index, length $length")
        val i = pieceIndexFor(index)
        return pieces[i].charAt(index - pieceStarts[i])
    }

    /** A zero-copy sub-snapshot of `[startIndex, endIndex)`. */
    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): Snapshot {
        checkRange(startIndex, endIndex)
        if (startIndex == endIndex) return EMPTY
        val first = pieceIndexFor(startIndex)
        val last = pieceIndexFor(endIndex - 1)
        val slice =
            Array(last - first + 1) { k ->
                val i = first + k
                val from = maxOf(startIndex, pieceStarts[i]) - pieceStarts[i]
                val to = minOf(endIndex, pieceStarts[i + 1]) - pieceStarts[i]
                pieces[i].slice(from, to)
            }
        return Snapshot(slice)
    }

    /** The whole text as one String: O(length), so meant for small slices and tests. */
    override fun toString(): String {
        val out = StringBuilder(length)
        forEachChunk { chars, start, count -> out.appendRange(chars, start, start + count) }
        return out.toString()
    }

    /** Offset of the first char of 0-based [line]. */
    public fun lineStartOffset(line: Int): Int {
        checkLine(line)
        if (line == 0) return 0
        // The piece holding the line-th '\n' is the last one with fewer before it.
        val i = lowerBound(lineFeedsBeforePiece, line) - 1
        return pieceStarts[i] + pieces[i].offsetAfterLineFeed(line - lineFeedsBeforePiece[i])
    }

    /** Offset just past the last char of [line], before its `'\n'`. */
    public fun lineEndOffset(line: Int): Int = if (line == lineCount - 1) length else lineStartOffset(line + 1) - 1

    /** The text of [line] without its line break. */
    public fun lineContent(line: Int): String = subSequence(lineStartOffset(line), lineEndOffset(line)).toString()

    /** The line and column of [offset], which may equal [length]. */
    public fun positionAt(offset: Int): TextPosition {
        if (offset < 0 || offset > length) throw IndexOutOfBoundsException("offset $offset, length $length")
        val line =
            if (offset == length) {
                lineCount - 1
            } else {
                val i = pieceIndexFor(offset)
                lineFeedsBeforePiece[i] + pieces[i].lineFeedsBefore(offset - pieceStarts[i])
            }
        return TextPosition(line, offset - lineStartOffset(line))
    }

    /** Copies the text into [out] chunk by chunk, without building one big String first. */
    public fun appendTo(out: Appendable) {
        forEachChunk { chars, start, count ->
            if (out is StringBuilder) {
                out.appendRange(
                    chars,
                    start,
                    start + count,
                )
            } else {
                out.append(String(chars, start, count))
            }
        }
    }

    /** Visits the text as raw buffer ranges, in order. Internal so no caller can write into a buffer. */
    internal inline fun forEachChunk(action: (chars: CharArray, start: Int, count: Int) -> Unit) {
        for (piece in pieces) if (piece.length > 0) action(piece.buffer.chars, piece.start, piece.length)
    }

    private fun pieceIndexFor(index: Int): Int {
        val guess = hint
        if (guess < pieces.size && index >= pieceStarts[guess] && index < pieceStarts[guess + 1]) return guess
        val next = guess + 1
        val found =
            if (next < pieces.size && index >= pieceStarts[next] && index < pieceStarts[next + 1]) {
                next
            } else {
                upperBound(pieceStarts, index) - 1
            }
        hint = found
        return found
    }

    private fun checkRange(
        start: Int,
        end: Int,
    ) {
        if (start < 0 || end > length ||
            start > end
        ) {
            throw IndexOutOfBoundsException("range [$start, $end), length $length")
        }
    }

    private fun checkLine(line: Int) {
        if (line < 0 || line >= lineCount) throw IndexOutOfBoundsException("line $line, line count $lineCount")
    }

    /** Snapshot-specific constants. */
    public companion object {
        /** The empty text. Shared, since it holds nothing that could change. */
        public val EMPTY: Snapshot = Snapshot(emptyArray())

        /** Index of the first element of sorted [values] that is at least [key]. */
        private fun lowerBound(
            values: IntArray,
            key: Int,
        ): Int {
            var low = 0
            var high = values.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (values[mid] < key) low = mid + 1 else high = mid
            }
            return low
        }

        /** Index of the first element of sorted [values] that is greater than [key]. */
        private fun upperBound(
            values: IntArray,
            key: Int,
        ): Int {
            var low = 0
            var high = values.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (values[mid] <= key) low = mid + 1 else high = mid
            }
            return low
        }
    }
}
