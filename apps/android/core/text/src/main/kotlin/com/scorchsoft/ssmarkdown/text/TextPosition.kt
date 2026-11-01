package com.scorchsoft.ssmarkdown.text

/**
 * A 0-based line and a 0-based column counted in UTF-16 chars.
 *
 * Columns are chars, not graphemes, because that is what every offset in
 * this module is; [GraphemeBoundaries] converts when a cursor has to move
 * by what a person sees as one character.
 */
public data class TextPosition(
    /** 0-based line index. */
    public val line: Int,
    /** 0-based char offset from the start of [line]. */
    public val column: Int,
) : Comparable<TextPosition> {
    /** Document order, so selections can be normalised with `minOf` / `maxOf`. */
    override fun compareTo(other: TextPosition): Int =
        compareValuesBy(this, other, TextPosition::line, TextPosition::column)
}
