package com.scorchsoft.ssmarkdown.markdown.code

import com.scorchsoft.ssmarkdown.markdown.TokenBuilder
import com.scorchsoft.ssmarkdown.markdown.TokenKind

/**
 * Colours one line of code inside a fenced block: `lex(line, state) ->
 * state`, the same per-line contract as the Markdown tokenizer, so the
 * [com.scorchsoft.ssmarkdown.markdown.IncrementalTokenizer] carries a
 * code block's state (an open block comment, a triple-quoted string, a
 * template literal) from line to line and stops re-lexing as soon as an
 * edit's effect dies out.
 *
 * A lexer paints [TokenKind] `CODE_*` runs over the block's
 * `CODE_BLOCK_TEXT`; what it leaves unpainted (identifiers, operators,
 * punctuation) stays plain code text, as in highlight.js. Instances keep
 * scratch fields, so each [com.scorchsoft.ssmarkdown.markdown.LineTokenizer]
 * owns its own ([CodeHighlighter]).
 */
@Suppress("TooManyFunctions") // Shared scanning helpers for every lexer.
internal abstract class CodeLexer {
    protected var text: CharSequence = ""
    protected var end: Int = 0
    protected lateinit var out: TokenBuilder

    /**
     * Paints `text[from, to)` starting in [state] (0 for a block's first
     * line) and returns the state the next line starts in, which must fit
     * in [CodeState.BITS] bits.
     */
    fun lex(
        line: CharSequence,
        from: Int,
        to: Int,
        state: Int,
        target: TokenBuilder,
    ): Int {
        text = line
        end = to
        out = target
        return lex(from, state)
    }

    /** [lex] with the line already in [text], [end] and [out]. */
    protected abstract fun lex(
        from: Int,
        state: Int,
    ): Int

    protected fun paint(
        from: Int,
        to: Int,
        kind: TokenKind,
    ) = out.paint(from, to, kind)

    protected fun matchesAt(
        i: Int,
        s: String,
    ): Boolean {
        if (i + s.length > end) return false
        for (k in s.indices) if (text[i + k] != s[k]) return false
        return true
    }

    /** [matchesAt] for a lower-case [s], ignoring the case of the text. */
    protected fun matchesAtIgnoreCase(
        i: Int,
        s: String,
    ): Boolean {
        if (i + s.length > end) return false
        for (k in s.indices) if (text[i + k].lowercaseChar() != s[k]) return false
        return true
    }

    protected fun skipSpaces(from: Int): Int {
        var i = from
        while (i < end && isSpace(text[i])) i++
        return i
    }

    /** End of a single-line string opened by [quote] at [open]; past the closing quote, or [end]. */
    protected fun stringEnd(
        open: Int,
        quote: Char,
        escapes: Boolean = true,
    ): Int {
        var j = open + 1
        while (j < end) {
            val c = text[j]
            when {
                c == '\\' && escapes -> j += 2
                c == quote -> return j + 1
                else -> j++
            }
        }
        return end
    }

    /** End of a number starting at [start]: digits, a radix prefix, a fraction, an exponent and a suffix. */
    protected fun numberEnd(start: Int): Int {
        val radix = text[start] == '0' && start + 1 < end && text[start + 1] in RADIX_MARKS
        var j = if (radix) digitsEnd(start + 2, hex = true) else exponentEnd(fractionEnd(digitsEnd(start, hex = false)))
        // Type suffixes: 10L, 1.5f, 10n, 5u32.
        while (j < end && isNamePart(text[j])) j++
        return j
    }

    /** Past the digits (and `_` separators) from [from]. */
    private fun digitsEnd(
        from: Int,
        hex: Boolean,
    ): Int {
        var j = from
        while (j < end && (text[j] == '_' || (if (hex) isHexDigit(text[j]) else text[j].isDigitAscii()))) j++
        return j
    }

    /** Past `.123` at [from], if there is one; a `.` not followed by a digit (`1..2`, `1.foo`) is not taken. */
    private fun fractionEnd(from: Int): Int =
        if (from + 1 < end && text[from] == '.' &&
            text[from + 1].isDigitAscii()
        ) {
            digitsEnd(from + 1, hex = false)
        } else {
            from
        }

    /** Past `e10`, `E-3` at [from], if there is one. */
    private fun exponentEnd(from: Int): Int {
        if (from >= end || (text[from] != 'e' && text[from] != 'E')) return from
        val signed = from + 1 < end && (text[from + 1] == '+' || text[from + 1] == '-')
        val digits = if (signed) from + 2 else from + 1
        return if (digits < end && text[digits].isDigitAscii()) digitsEnd(digits, hex = false) else from
    }

    protected companion object {
        private const val RADIX_MARKS = "xXbBoO"

        fun isSpace(c: Char): Boolean = c == ' ' || c == '\t'

        fun Char.isDigitAscii(): Boolean = this in '0'..'9'

        fun isHexDigit(c: Char): Boolean = c.isDigitAscii() || c in 'a'..'f' || c in 'A'..'F'

        fun isNameStart(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c == '_' || (c.code > ASCII && c.isLetter())

        fun isNamePart(c: Char): Boolean = isNameStart(c) || c.isDigitAscii()

        private const val ASCII = 0x7F
    }
}

/**
 * The line state a [CodeLexer] hands to the next line, packed into
 * [BITS] bits of [com.scorchsoft.ssmarkdown.markdown.LineState]: a mode
 * (what is open), a depth (nested comments, template braces) and an
 * extra byte (which delimiter, an indent). Zero is "nothing open".
 */
internal object CodeState {
    const val BITS = 16
    const val MAX_DEPTH = 15
    const val MAX_EXTRA = 0xFF
    private const val MODE_MASK = 0xF
    private const val DEPTH_SHIFT = 4
    private const val EXTRA_SHIFT = 8

    fun pack(
        mode: Int,
        depth: Int = 0,
        extra: Int = 0,
    ): Int = mode or (depth.coerceIn(0, MAX_DEPTH) shl DEPTH_SHIFT) or (extra.coerceIn(0, MAX_EXTRA) shl EXTRA_SHIFT)

    fun mode(state: Int): Int = state and MODE_MASK

    fun depth(state: Int): Int = (state ushr DEPTH_SHIFT) and MODE_MASK

    fun extra(state: Int): Int = (state ushr EXTRA_SHIFT) and MAX_EXTRA
}
