package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.markdown.TokenKind
import com.scorchsoft.ssmarkdown.markdown.Tokens

/** Receives the runs [RunPlanner] decides on, in column order. */
fun interface RunSink {
    /** Draw columns `[start, end)` as [kind] inside the InlineStyle [flags]. */
    fun run(
        start: Int,
        end: Int,
        kind: TokenKind,
        flags: Int,
    )
}

/**
 * Turns a line's packed tokens into the runs one row of it is drawn in:
 * clipped to the visible columns, split wherever the token (and so the
 * style) changes, and split around tabs, which are drawn as gaps rather
 * than glyphs. Columns past the last token (the untokenised tail of a very
 * long line) are [TokenKind.PLAIN].
 *
 * Pure and allocation-free per call, so the decision "what is drawn how"
 * is tested on the host without a canvas.
 */
object RunPlanner {
    /**
     * Plans columns `[from, to)` of a line with [tokens]. [chars] holds the
     * line's chars from column [charsStart]; it must cover `[from, to)`.
     */
    @Suppress("LongParameterList") // A hot draw-path call: plain values, no wrapper allocation per row.
    fun plan(
        tokens: LongArray,
        from: Int,
        to: Int,
        chars: CharArray,
        charsStart: Int,
        sink: RunSink,
    ) {
        var index = firstTokenEndingAfter(tokens, from)
        var column = from
        while (column < to) {
            val token = if (index < tokens.size) tokens[index] else NO_TOKEN
            val covers = token != NO_TOKEN && Tokens.start(token) <= column
            val segmentEnd =
                when {
                    covers -> minOf(to, Tokens.end(token))
                    token != NO_TOKEN -> minOf(to, Tokens.start(token))
                    else -> to
                }
            val kind = if (covers) Tokens.kind(token) else TokenKind.PLAIN
            val flags = if (covers) Tokens.styles(token) else 0
            emitAroundTabs(column, segmentEnd, kind, flags, chars, charsStart, sink)
            column = segmentEnd
            if (covers && column >= Tokens.end(token)) index++
        }
    }

    @Suppress("LongParameterList") // See plan().
    private fun emitAroundTabs(
        start: Int,
        end: Int,
        kind: TokenKind,
        flags: Int,
        chars: CharArray,
        charsStart: Int,
        sink: RunSink,
    ) {
        var runStart = start
        for (column in start until end) {
            if (chars[column - charsStart] == '\t') {
                if (column > runStart) sink.run(runStart, column, kind, flags)
                runStart = column + 1
            }
        }
        if (end > runStart) sink.run(runStart, end, kind, flags)
    }

    /** Index of the first token whose end is past [column]; tokens are sorted and contiguous. */
    private fun firstTokenEndingAfter(
        tokens: LongArray,
        column: Int,
    ): Int {
        var low = 0
        var high = tokens.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (Tokens.end(tokens[mid]) <= column) low = mid + 1 else high = mid
        }
        return low
    }

    /** No packed token is ever all ones: its kind byte would be 255. */
    private const val NO_TOKEN = -1L
}
