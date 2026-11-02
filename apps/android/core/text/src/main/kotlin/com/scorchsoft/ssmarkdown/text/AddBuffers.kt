package com.scorchsoft.ssmarkdown.text

/**
 * Where typed and pasted text is stored: a chain of append-only
 * [TextBuffer]s. Only the newest is written to; older ones are full and,
 * like loaded chunks, never change again.
 *
 * VS Code keeps one ever-growing "change buffer" string. Fixed-capacity
 * chunks give the same append-in-place fast path without ever reallocating
 * text another thread may be reading.
 */
internal class AddBuffers {
    private var current: TextBuffer = TextBuffer.appendable()

    /** True when typing at the end of [piece] can grow it in place. */
    fun canExtend(piece: Piece): Boolean = piece.buffer === current && piece.end == current.length

    fun fitsInPlace(text: CharSequence): Boolean = text.length <= current.remaining

    /** Appends [text] to the current buffer and returns [piece] grown to cover it. */
    fun extend(
        piece: Piece,
        text: CharSequence,
    ): Piece {
        current.append(text, 0, text.length)
        return Piece(current, current.lineStarts, piece.start, current.length, piece.startLine, current.lineCount - 1)
    }

    /** Stores [text] and returns the pieces that cover it, in order. */
    fun store(text: CharSequence): List<Piece> {
        if (text.length > current.remaining) current = TextBuffer.appendable()
        val pieces = ArrayList<Piece>(1 + text.length / TextBuffer.CAPACITY)
        var from = 0
        while (from < text.length) {
            var to = minOf(text.length, from + current.remaining)
            // Keep a surrogate pair inside one buffer, as the loader does.
            if (to < text.length && text[to - 1].isHighSurrogate()) to--
            if (to > from) {
                pieces += appendPiece(text, from, to)
                from = to
            } else {
                current = TextBuffer.appendable()
            }
        }
        return pieces
    }

    private fun appendPiece(
        text: CharSequence,
        from: Int,
        to: Int,
    ): Piece {
        val start = current.length
        val startLine = current.lineCount - 1
        current.append(text, from, to)
        return Piece(current, current.lineStarts, start, current.length, startLine, current.lineCount - 1)
    }
}
