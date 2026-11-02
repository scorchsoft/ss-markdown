package com.scorchsoft.ssmarkdown.markdown

/**
 * How a line's tokens are packed: one `Long` per token in a [LongArray].
 *
 * A 200k-line document keeps a token array for every line it has seen, so
 * an object per token (header, three fields, a pointer) would cost several
 * times the text itself. Packed, a token is 8 bytes and a line's tokens are
 * one allocation:
 *
 * ```
 * bits  0..15  start column (inclusive)
 * bits 16..31  end column (exclusive)
 * bits 32..39  TokenKind ordinal
 * bits 40..47  InlineStyle flags
 * ```
 *
 * Sixteen bits are enough because only the first [LineTokenizer.MAX_TOKENIZED_COLUMNS]
 * characters of a line are ever tokenised (docs/robustness.md, "Long lines
 * are chunked"). The tokens of a line are sorted, never overlap and cover
 * `[0, min(length, MAX_TOKENIZED_COLUMNS))` without gaps; anything past the
 * last token is [TokenKind.PLAIN]. Adjacent runs with the same kind and
 * flags are always merged, so two tokenisations are equal exactly when their
 * arrays are.
 */
public object Tokens {
    private const val COLUMN_MASK = 0xFFFFL
    private const val BYTE_MASK = 0xFFL
    private const val END_SHIFT = 16
    private const val KIND_SHIFT = 32
    private const val STYLE_SHIFT = 40

    /** Packs one token. [start] and [end] must lie in `0..65535`. */
    public fun pack(
        start: Int,
        end: Int,
        kind: TokenKind,
        styles: Int = 0,
    ): Long =
        (start.toLong() and COLUMN_MASK) or
            ((end.toLong() and COLUMN_MASK) shl END_SHIFT) or
            (kind.ordinal.toLong() shl KIND_SHIFT) or
            ((styles.toLong() and BYTE_MASK) shl STYLE_SHIFT)

    /** First column of [token]. */
    public fun start(token: Long): Int = (token and COLUMN_MASK).toInt()

    /** Column just past [token]. */
    public fun end(token: Long): Int = ((token ushr END_SHIFT) and COLUMN_MASK).toInt()

    /** What [token] is. */
    public fun kind(token: Long): TokenKind = TokenKind.ALL[((token ushr KIND_SHIFT) and BYTE_MASK).toInt()]

    /** The [InlineStyle] flags around [token]. */
    public fun styles(token: Long): Int = ((token ushr STYLE_SHIFT) and BYTE_MASK).toInt()

    /** A readable form for test failures and debugging, e.g. `0-2:HEADING_MARKER 2-7:HEADING_TEXT`. */
    public fun describe(tokens: LongArray): String =
        tokens.joinToString(" ") { token ->
            val styles = styles(token)
            "${start(token)}-${end(token)}:${kind(token)}" + if (styles != 0) "/$styles" else ""
        }
}
