// Recognisers bail out the moment a construct fails to match, as CommonMark's
// own reference parsers do; one exit per function would bury the grammar in flags.
package com.scorchsoft.ssmarkdown.markdown

/**
 * Recognisers for inline constructs that are matched in one forward scan:
 * link destinations and titles, autolinks and raw HTML tags. Each returns
 * the index just past the construct, or -1 when there is none, and never
 * reads at or past `end`.
 */
@Suppress("TooManyFunctions") // One small recogniser per inline construct, plus the char classes they share.
internal object InlineSyntax {
    private const val MIN_SCHEME = 2
    private const val MAX_SCHEME = 32
    private const val ASCII_END = 0x80
    private const val NO_QUOTE = '\u0000'
    private const val COMMENT_OPEN = "<!--"
    private const val COMMENT_CLOSE = "-->"
    private const val EMAIL_LOCAL_PUNCTUATION = ".!#$%&'*+/=?^_`{|}~-"
    private const val URL_TRAILING_PUNCTUATION = "?!.,:*_~'\";"
    private val BARE_PREFIXES = arrayOf("https://", "http://", "www.")

    private val PUNCTUATION_TYPES: Set<Int> =
        setOf(
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            Character.MATH_SYMBOL,
            Character.CURRENCY_SYMBOL,
            Character.MODIFIER_SYMBOL,
            Character.OTHER_SYMBOL,
        ).map { it.toInt() }.toSet()

    fun isAsciiPunctuation(c: Char): Boolean = c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~'

    /** Unicode punctuation and symbols, as CommonMark 0.31 counts them for emphasis flanking. */
    fun isPunctuation(c: Char): Boolean =
        if (c.code < ASCII_END) isAsciiPunctuation(c) else Character.getType(c) in PUNCTUATION_TYPES

    /** End of a link destination at [from]: `<...>`, or a run without spaces whose parentheses balance. */
    fun destinationEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        if (from >= end) return -1
        return if (text[from] == '<') pointyDestinationEnd(text, from, end) else bareDestinationEnd(text, from, end)
    }

    private fun pointyDestinationEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        var i = from + 1
        while (i < end) {
            when (text[i]) {
                '\\' -> i++
                '<' -> return -1
                '>' -> return i + 1
            }
            i++
        }
        return -1
    }

    private fun bareDestinationEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        var depth = 0
        var i = from
        while (i < end && !endsBareDestination(text[i], depth)) {
            when (text[i]) {
                '\\' -> if (i + 1 < end) i++
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return if (depth == 0 && i > from) i else -1
    }

    private fun endsBareDestination(
        c: Char,
        depth: Int,
    ): Boolean = c.code <= ' '.code || (c == ')' && depth == 0)

    /** End of a link title at [from]: `"..."`, `'...'` or `(...)`. */
    fun titleEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        if (from >= end) return -1
        val close =
            when (text[from]) {
                '"' -> '"'
                '\'' -> '\''
                '(' -> ')'
                else -> return -1
            }
        var i = from + 1
        while (i < end) {
            val c = text[i]
            when {
                c == '\\' -> i++
                c == close -> return i + 1
                close == ')' && c == '(' -> return -1
            }
            i++
        }
        return -1
    }

    /** `<scheme:...>` or `<user@host>`. */
    fun autolinkEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int = uriAutolinkEnd(text, from, end).let { if (it > 0) it else emailAutolinkEnd(text, from, end) }

    private fun uriAutolinkEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        var i = from + 1
        if (i >= end || !text[i].isAsciiLetter()) return -1
        while (i < end && text[i].isSchemeChar()) i++
        if (i - from - 1 !in MIN_SCHEME..MAX_SCHEME || i >= end || text[i] != ':') return -1
        while (i < end && text[i] != '>') {
            if (text[i] == '<' || text[i].code <= ' '.code) return -1
            i++
        }
        return if (i < end) i + 1 else -1
    }

    private fun emailAutolinkEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        var i = from + 1
        while (i < end && text[i].isEmailLocalChar()) i++
        if (i == from + 1 || i >= end || text[i] != '@') return -1
        val hostStart = ++i
        while (i < end && text[i].isHostChar()) i++
        return if (i > hostStart && i < end && text[i] == '>') i + 1 else -1
    }

    /** An inline HTML open tag, closing tag or comment at [from]. Simplified: attributes are not validated. */
    fun htmlTagEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        if (BlockSyntax.regionMatches(text, from, COMMENT_OPEN)) return commentEnd(text, from, end)
        var i = from + 1
        if (i < end && text[i] == '/') i++
        if (i >= end || !text[i].isAsciiLetter()) return -1
        while (i < end && text[i].isTagNameChar()) i++
        if (i < end && !text[i].endsTagName()) return -1
        return tagCloseEnd(text, i, end)
    }

    private fun commentEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        for (i in from + COMMENT_OPEN.length..end - COMMENT_CLOSE.length) {
            if (BlockSyntax.regionMatches(text, i, COMMENT_CLOSE)) return i + COMMENT_CLOSE.length
        }
        return -1
    }

    /** Skips attributes (a quoted value may hold `>`) to the tag's closing `>`. */
    private fun tagCloseEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        var quote = NO_QUOTE
        for (i in from until end) {
            val c = text[i]
            when {
                quote != NO_QUOTE -> if (c == quote) quote = NO_QUOTE
                c == '"' || c == '\'' -> quote = c
                c == '<' -> return -1
                c == '>' -> return i + 1
            }
        }
        return -1
    }

    /**
     * A bare GFM autolink (`https://...`, `www....`) at [from], or -1. Trailing
     * punctuation and an unbalanced `)` stay outside, as on GitHub.
     */
    fun bareUrlEnd(
        text: CharSequence,
        from: Int,
        lineStart: Int,
        end: Int,
    ): Int {
        if (from > lineStart && !isUrlBoundary(text[from - 1])) return -1
        val prefix = BARE_PREFIXES.firstOrNull { BlockSyntax.regionMatches(text, from, it) } ?: return -1
        val bodyStart = from + prefix.length
        var stop = bodyStart
        while (stop < end && !text[stop].isWhitespace() && text[stop] != '<') stop++
        while (stop > bodyStart && text[stop - 1] in URL_TRAILING_PUNCTUATION) stop--
        if (stop > bodyStart && text[stop - 1] == ')' && !balanced(text, from, stop)) stop--
        return if (stop > bodyStart) stop else -1
    }

    private fun isUrlBoundary(c: Char): Boolean = c.isWhitespace() || c in "(*_~"

    private fun balanced(
        text: CharSequence,
        from: Int,
        to: Int,
    ): Boolean {
        var depth = 0
        for (i in from until to) {
            if (text[i] == '(') {
                depth++
            } else if (text[i] == ')') {
                depth--
            }
        }
        return depth >= 0
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'

    private fun Char.isSchemeChar(): Boolean = isAsciiLetterOrDigit() || this in "+.-"

    private fun Char.isEmailLocalChar(): Boolean = isAsciiLetterOrDigit() || this in EMAIL_LOCAL_PUNCTUATION

    private fun Char.isHostChar(): Boolean = isAsciiLetterOrDigit() || this == '.' || this == '-'

    private fun Char.isTagNameChar(): Boolean = isAsciiLetterOrDigit() || this == '-'

    private fun Char.endsTagName(): Boolean = this == '>' || this == '/' || BlockSyntax.isSpace(this)
}
