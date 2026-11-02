package com.scorchsoft.ssmarkdown.text

/**
 * One chunk of document text: at most [CAPACITY] chars plus the offsets at
 * which its lines start (docs/robustness.md, "Text model").
 *
 * The rule that makes snapshots safe: **a char, once written, is never
 * rewritten.** A loaded chunk is sealed at birth. An add buffer only ever
 * grows at its end, and only on the editing thread, so every range a piece
 * already points at stays valid for as long as anyone holds that piece.
 *
 * Text here is always normalised: the only line break is `'\n'`, so a line
 * start is simply "one past a `'\n'`" and a CRLF can never be split.
 */
internal class TextBuffer private constructor(
    val chars: CharArray,
    length: Int,
    lineStarts: IntArray,
    lineCount: Int,
) {
    /** Chars written so far; only an add buffer's grows. */
    var length: Int = length
        private set

    /**
     * Line start offsets; index 0 is always 0. Pieces capture the array they
     * were created with, so growing it (copy, then swap) never disturbs a
     * reader holding the old one: the entries it can see are never changed.
     */
    var lineStarts: IntArray = lineStarts
        private set

    /** Number of valid entries in [lineStarts]. */
    var lineCount: Int = lineCount
        private set

    val remaining: Int get() = chars.size - length

    /** Appends `text[from, to)` to an add buffer. The caller checks [remaining]. */
    fun append(
        text: CharSequence,
        from: Int,
        to: Int,
    ) {
        val count = to - from
        check(count <= remaining) { "Add buffer overflow: $count > $remaining" }
        if (text is String) {
            text.toCharArray(chars, length, from, to)
        } else {
            for (i in 0 until count) chars[length + i] = text[from + i]
        }
        for (i in length until length + count) {
            if (chars[i] == '\n') pushLineStart(i + 1)
        }
        length += count
    }

    private fun pushLineStart(offset: Int) {
        if (lineCount == lineStarts.size) lineStarts = lineStarts.copyOf(lineStarts.size * 2)
        lineStarts[lineCount++] = offset
    }

    companion object {
        /**
         * VS Code's `AverageBufferSize`: big enough that a 100 MB file is
         * only ~1,600 chunks, small enough that copying one is cheap.
         */
        const val CAPACITY: Int = 65_535

        private const val INITIAL_ADD_LINE_STARTS = 64

        /** A zero-length chunk, the backing for the tree's sentinel piece. */
        val EMPTY: TextBuffer = sealed(CharArray(0), 0)

        /** A read-only chunk over `chars[0, length)`. Takes ownership of [chars]. */
        fun sealed(
            chars: CharArray,
            length: Int,
        ): TextBuffer {
            var count = 1
            for (i in 0 until length) if (chars[i] == '\n') count++
            val starts = IntArray(count)
            var next = 1
            for (i in 0 until length) if (chars[i] == '\n') starts[next++] = i + 1
            return TextBuffer(chars, length, starts, count)
        }

        /** An empty add buffer for typed and pasted text. */
        fun appendable(): TextBuffer = TextBuffer(CharArray(CAPACITY), 0, IntArray(INITIAL_ADD_LINE_STARTS), 1)
    }
}
