package com.scorchsoft.ssmarkdown.markdown

/**
 * A scratch canvas with one kind byte and one style byte per column. The
 * tokenizer paints it in passes (block markers, then code spans and other
 * atomic spans, then links, then emphasis) and [build] run-length encodes
 * the result into packed [Tokens].
 *
 * Painting rather than building a token tree keeps nesting trivial: an
 * inner rule paints over an outer one, and an outer *style* is a flag OR-ed
 * across its range. The arrays are reused from line to line, so a line
 * costs no allocation beyond its final `LongArray`.
 */
internal class TokenBuilder {
    private var kinds = ByteArray(INITIAL_CAPACITY)
    private var styles = ByteArray(INITIAL_CAPACITY)

    /** Columns being painted: the tokenised prefix of the line. */
    var length: Int = 0
        private set

    fun reset(length: Int) {
        if (length > kinds.size) {
            val capacity = maxOf(length, kinds.size * 2)
            kinds = ByteArray(capacity)
            styles = ByteArray(capacity)
        } else {
            kinds.fill(0, 0, length)
            styles.fill(0, 0, length)
        }
        this.length = length
    }

    fun kindAt(column: Int): TokenKind = TokenKind.ALL[kinds[column].toInt()]

    /** Paints `[from, to)` (clipped to [length]) as [kind]. */
    fun paint(
        from: Int,
        to: Int,
        kind: TokenKind,
    ) {
        val end = minOf(to, length)
        if (from < end) kinds.fill(kind.ordinal.toByte(), from, end)
    }

    /** Paints only the columns of `[from, to)` that are still [where]: an outer rule never hides an inner one. */
    fun paintOver(
        from: Int,
        to: Int,
        kind: TokenKind,
        where: TokenKind,
    ) {
        val end = minOf(to, length)
        val target = where.ordinal.toByte()
        val value = kind.ordinal.toByte()
        for (column in from until end) if (kinds[column] == target) kinds[column] = value
    }

    /** ORs [style] into every column of `[from, to)`. */
    fun addStyle(
        from: Int,
        to: Int,
        style: Int,
    ) {
        val end = minOf(to, length)
        for (column in from until end) styles[column] = (styles[column].toInt() or style).toByte()
    }

    fun build(): LongArray {
        if (length == 0) return EMPTY
        var count = 1
        for (column in 1 until length) if (changesAt(column)) count++
        val out = LongArray(count)
        var start = 0
        var index = 0
        for (column in 1..length) {
            if (column == length || changesAt(column)) {
                out[index++] = Tokens.pack(start, column, TokenKind.ALL[kinds[start].toInt()], styles[start].toInt())
                start = column
            }
        }
        return out
    }

    private fun changesAt(column: Int): Boolean =
        kinds[column] != kinds[column - 1] || styles[column] != styles[column - 1]

    private companion object {
        const val INITIAL_CAPACITY = 256
        val EMPTY = LongArray(0)
    }
}
