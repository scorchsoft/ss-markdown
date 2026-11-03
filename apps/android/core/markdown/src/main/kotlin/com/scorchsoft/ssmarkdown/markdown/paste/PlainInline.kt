package com.scorchsoft.ssmarkdown.markdown.paste

/**
 * One line of inline Markdown as the words it shows: escapes resolved,
 * code spans as their code, links as their text, pictures as their alt
 * text, autolinks as their address, tags and footnote references gone, and
 * emphasis delimiters (`*`, `_`, `~`, `^`, `==`) removed where they pair up
 * as CommonMark pairs them; a delimiter with no partner stays, as it shows.
 */
internal object PlainInline {
    private val autolink = Regex("""^<([A-Za-z][A-Za-z0-9+.-]{1,31}:[^ \t\n<>]*)>""")
    private val email = Regex("""^<([^ \t\n<>@]+@[^ \t\n<>@]+)>""")
    private val comment = Regex("""^<!--[\s\S]*?-->""")
    private val tag = Regex("""^</?[A-Za-z][A-Za-z0-9-]*(?:[ \t\n][^<>]*)?/?>""")
    private val br = Regex("""^<[Bb][Rr][ \t\n/>]""")
    private val footnote = Regex("""^\[\^[^\]\s]+\]""")
    private val entity = Regex("""^&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});""")
    private const val DELIMITERS = "*_~^="

    /** A literal run of text, or a run of one delimiter character still to be paired. */
    private class Token(
        val text: String,
        val delimiter: Char? = null,
        var count: Int = 0,
        var canOpen: Boolean = false,
        var canClose: Boolean = false,
    )

    fun of(text: String): String {
        val tokens = tokenize(text)
        pair(tokens)
        return tokens.joinToString("") { t -> t.delimiter?.toString()?.repeat(t.count) ?: t.text }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth") // One scan, a branch per kind of syntax.
    private fun tokenize(s: String): MutableList<Token> {
        val tokens = mutableListOf<Token>()
        val literal = StringBuilder()

        fun flush() {
            if (literal.isNotEmpty()) tokens += Token(literal.toString())
            literal.clear()
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && isAsciiPunctuation(s[i + 1]) -> {
                    literal.append(s[i + 1])
                    i += 2
                }

                c == '`' -> {
                    i = codeSpan(s, i, literal)
                }

                c == '!' && s.startsWith("![", i) -> {
                    val link = Links.parse(s, i + 1)
                    if (link ==
                        null
                    ) {
                        literal.append(c).also { i++ }
                    } else {
                        literal.append(of(link.text)).also { i = link.end }
                    }
                }

                c == '[' -> {
                    val note = footnote.find(s.substring(i))
                    val link = if (note == null) Links.parse(s, i) else null
                    when {
                        note != null -> i += note.value.length
                        link != null -> literal.append(of(link.text)).also { i = link.end }
                        else -> literal.append(c).also { i++ }
                    }
                }

                c == '<' -> {
                    val rest = s.substring(i)
                    val m = autolink.find(rest) ?: email.find(rest)
                    val skip = comment.find(rest) ?: tag.find(rest)
                    when {
                        m != null -> {
                            literal.append(m.groupValues[1]).also { i += m.value.length }
                        }

                        skip != null -> {
                            if (br.containsMatchIn(skip.value)) literal.append(' ')
                            i += skip.value.length
                        }

                        else -> {
                            literal.append(c).also { i++ }
                        }
                    }
                }

                c == '&' && entity.containsMatchIn(s.substring(i)) -> {
                    val m = entity.find(s.substring(i))!!
                    literal.append(HtmlEntities.decode(m.value))
                    i += m.value.length
                }

                c in DELIMITERS -> {
                    var end = i
                    while (end < s.length && s[end] == c) end++
                    val count = end - i
                    if (c == '=' && count < 2) {
                        literal.append(c)
                    } else {
                        flush()
                        tokens += delimiter(c, count, s.getOrNull(i - 1), s.getOrNull(end))
                    }
                    i = end
                }

                else -> {
                    literal.append(c).also { i++ }
                }
            }
        }
        flush()
        return tokens
    }

    /** A code span at [start]: its code, unchanged; or the backticks as text when it never closes. */
    private fun codeSpan(
        s: String,
        start: Int,
        literal: StringBuilder,
    ): Int {
        var end = start
        while (end < s.length && s[end] == '`') end++
        val run = end - start
        var search = end
        while (search < s.length) {
            val open = s.indexOf('`', search)
            if (open < 0) break
            var close = open
            while (close < s.length && s[close] == '`') close++
            if (close - open == run) {
                val code = s.substring(end, open)
                literal.append(if (paddedBothEnds(code)) code.substring(1, code.length - 1) else code)
                return close
            }
            search = close
        }
        literal.append("`".repeat(run))
        return end
    }

    /** CommonMark strips one space from each end of code padded on both ends (and not all spaces). */
    private fun paddedBothEnds(code: String): Boolean =
        code.length >= 2 && code.startsWith(' ') && code.endsWith(' ') && code.isNotBlank()

    /** CommonMark's flanking rules for a run of [c] between [before] and [after] (null: the line's edge). */
    @Suppress("CyclomaticComplexMethod") // CommonMark's flanking rules, one condition each.
    private fun delimiter(
        c: Char,
        count: Int,
        before: Char?,
        after: Char?,
    ): Token {
        val spaceBefore = before == null || isSpace(before)
        val spaceAfter = after == null || isSpace(after)
        val punctBefore = before != null && isPunctuation(before)
        val punctAfter = after != null && isPunctuation(after)
        val left = !spaceAfter && (!punctAfter || spaceBefore || punctBefore)
        val right = !spaceBefore && (!punctBefore || spaceAfter || punctAfter)
        val underscore = c == '_'
        return Token(
            text = "",
            delimiter = c,
            count = count,
            canOpen = if (underscore) left && (!right || punctBefore) else left,
            canClose = if (underscore) right && (!left || punctAfter) else right,
        )
    }

    /** Pairs closers with the nearest openers of the same character; paired characters are removed. */
    @Suppress("CyclomaticComplexMethod") // CommonMark's delimiter pairing, simplified.
    private fun pair(tokens: List<Token>) {
        for ((ci, closer) in tokens.withIndex()) {
            if (closer.delimiter == null || !closer.canClose) continue
            var oi = ci - 1
            while (closer.count > 0 && oi >= 0) {
                val opener = tokens[oi]
                val use = if (closer.count >= 2 && opener.count >= 2) 2 else 1
                val usable = opener.delimiter == closer.delimiter && opener.canOpen && opener.count > 0
                if (!usable || (closer.delimiter == '=' && use < 2)) {
                    oi--
                    continue
                }
                opener.count -= use
                closer.count -= use
                // Delimiters between the pair can no longer pair across it.
                for (k in oi + 1 until ci) {
                    tokens[k].canOpen = false
                    tokens[k].canClose = false
                }
                if (opener.count == 0) oi--
            }
        }
    }

    private fun isAsciiPunctuation(c: Char): Boolean = c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~'

    private fun isSpace(c: Char): Boolean = c.isWhitespace()

    private fun isPunctuation(c: Char): Boolean =
        isAsciiPunctuation(c) ||
            when (Character.getType(c).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION,
                -> true

                else -> false
            }
}

/** `[text](address)` and `[text][ref]` from a `[`: the text and where the link ends. */
internal object Links {
    class Link(
        val text: String,
        val end: Int,
    )

    fun parse(
        s: String,
        open: Int,
    ): Link? {
        val close = closing(s, open, '[', ']') ?: return null
        val text = s.substring(open + 1, close)
        return when (s.getOrNull(close + 1)) {
            '(' -> closing(s, close + 1, '(', ')')?.let { Link(text, it + 1) }
            '[' -> s.indexOf(']', close + 2).takeIf { it >= 0 }?.let { Link(text, it + 1) }
            else -> null
        }
    }

    /** The index of the [right] that closes the [left] at [open], counting nesting and skipping escapes. */
    private fun closing(
        s: String,
        open: Int,
        left: Char,
        right: Char,
    ): Int? {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                left -> depth++
                right -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }
}
