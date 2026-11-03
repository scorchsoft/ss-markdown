package com.scorchsoft.ssmarkdown.markdown.code

import com.scorchsoft.ssmarkdown.markdown.TokenKind

/**
 * CSS, SCSS and Less. A line that opens a rule (it has a `{`, or ends in
 * `,`) is a selector until its `{`: type selectors there are coloured as
 * highlight.js's `selector-tag` (keyword colour); classes, ids and
 * pseudo-classes stay plain. Elsewhere a name followed by `:` is a
 * property, and what follows it a value: numbers with their units, hex
 * colours, strings, functions. At-rules are keywords; `$name` (SCSS) and
 * Less's `@name` variables are variables. Only a block comment is carried
 * to the next line.
 */
@Suppress("TooManyFunctions") // One small function per kind of token; together they are the lexer.
internal class CssLexer(
    /** True for SCSS and Less: `//` comments and `$`/`@` variables. */
    private val preprocessor: Boolean,
    /** True for Less, where an unknown `@name` is a variable. */
    private val less: Boolean,
) : CodeLexer() {
    private var selector = false
    private var value = false

    override fun lex(
        from: Int,
        state: Int,
    ): Int {
        var i = from
        if (state == COMMENT) {
            i = comment(from, from)
            if (i >= end && !closedComment) return COMMENT
        }
        selector = opensRule(i)
        value = false
        var inComment = false
        while (i < end) {
            val c = text[i]
            when {
                matchesAt(i, "/*") -> {
                    i = comment(i + 2, i)
                    inComment = !closedComment
                }

                preprocessor && matchesAt(i, "//") -> {
                    paint(i, end, TokenKind.CODE_COMMENT)
                    i = end
                }

                else -> {
                    i = token(i, c)
                }
            }
        }
        return if (inComment) COMMENT else 0
    }

    private var closedComment = false

    private fun comment(
        from: Int,
        paintFrom: Int,
    ): Int {
        var j = from
        while (j < end && !matchesAt(j, "*/")) j++
        closedComment = j < end
        val close = if (closedComment) j + 2 else end
        paint(paintFrom, close, TokenKind.CODE_COMMENT)
        return close
    }

    /** True if the line holds a `{` or ends in `,`: a selector line. */
    private fun opensRule(from: Int): Boolean {
        var last = ' '
        for (j in from until end) {
            val c = text[j]
            if (c == '{') return true
            if (c == ':' && last != ' ' && !selectorChar(last)) return false
            if (!isSpace(c)) last = c
        }
        return last == ','
    }

    private fun selectorChar(c: Char): Boolean = isNamePart(c) || c == ')' || c == ']' || c == '&' || c == '*'

    @Suppress("CyclomaticComplexMethod") // One branch per kind of token.
    private fun token(
        i: Int,
        c: Char,
    ): Int =
        when {
            c == '"' || c == '\'' -> {
                paintTo(i, stringEnd(i, c), TokenKind.CODE_STRING)
            }

            c == '{' || c == ';' || c == '}' -> {
                selector = false
                value = false
                i + 1
            }

            c == '@' && i + 1 < end && isNameStart(text[i + 1]) -> {
                val j = nameEnd(i + 1)
                val atRule = AT_RULES.contains(text, i + 1, j)
                paintTo(i, j, if (less && !atRule) TokenKind.CODE_VARIABLE else TokenKind.CODE_KEYWORD)
            }

            c == '$' && preprocessor && i + 1 < end && isNameStart(text[i + 1]) -> {
                paintTo(i, nameEnd(i + 1), TokenKind.CODE_VARIABLE)
            }

            c == '#' && value -> {
                hexColour(i)
            }

            c == '!' && matchesAt(i + 1, "important") -> {
                paintTo(i, i + 1 + IMPORTANT, TokenKind.CODE_META)
            }

            c.isDigitAscii() || (c == '.' && i + 1 < end && text[i + 1].isDigitAscii() && !nameBefore(i)) -> {
                number(i)
            }

            isNameStart(c) || (c == '-' && i + 1 < end && isNameStart(text[i + 1])) -> {
                name(i)
            }

            else -> {
                i + 1
            }
        }

    private fun name(i: Int): Int {
        val j = nameEnd(i)
        val next = skipSpaces(j)
        val follows = if (next < end) text[next] else ' '
        return when {
            follows == '(' -> {
                paint(i, j, TokenKind.CODE_BUILT_IN)
                if (j - i == URL.length && matchesAtIgnoreCase(i, URL)) url(next) else j
            }

            !selector && !value && follows == ':' -> {
                paint(i, j, TokenKind.CODE_ATTRIBUTE)
                value = true
                next + 1
            }

            selector && startsCompound(i) -> {
                paintTo(i, j, TokenKind.CODE_KEYWORD)
            }

            else -> {
                j
            }
        }
    }

    /** A type selector starts a compound: after a space, a comma or a combinator, not after `.`, `#` or `:`. */
    private fun startsCompound(i: Int): Boolean = i == 0 || text[i - 1] in COMBINATORS

    /** `url(path)`: an unquoted path is a string, as in highlight.js. */
    private fun url(open: Int): Int {
        val start = skipSpaces(open + 1)
        if (start < end && (text[start] == '"' || text[start] == '\'')) return start
        var j = start
        while (j < end && text[j] != ')') j++
        paint(start, j, TokenKind.CODE_STRING)
        return j
    }

    private fun hexColour(i: Int): Int {
        var j = i + 1
        while (j < end && isHexDigit(text[j])) j++
        val digits = j - i - 1
        val isColour = digits in HEX_LENGTHS && (j >= end || !isNamePart(text[j]))
        return if (isColour) paintTo(i, j, TokenKind.CODE_NUMBER) else i + 1
    }

    /** A number and its unit: `12px`, `1.5em`, `50%`. */
    private fun number(i: Int): Int {
        var j = i
        while (j < end && (text[j].isDigitAscii() || text[j] == '.')) j++
        while (j < end && (text[j].isLetter() || text[j] == '%')) j++
        return paintTo(i, j, TokenKind.CODE_NUMBER)
    }

    private fun nameBefore(i: Int): Boolean = i > 0 && (isNamePart(text[i - 1]) || text[i - 1] == '-')

    private fun nameEnd(from: Int): Int {
        var j = from
        while (j < end && (isNamePart(text[j]) || text[j] == '-')) j++
        return j
    }

    private fun paintTo(
        from: Int,
        to: Int,
        kind: TokenKind,
    ): Int {
        paint(from, to, kind)
        return to
    }

    private companion object {
        const val COMMENT = 1
        const val URL = "url"
        const val COMBINATORS = " \t,>+~"
        const val IMPORTANT = 9
        val HEX_LENGTHS = setOf(3, 4, 6, 8)
        val AT_RULES =
            WordTable.of(
                "media import charset font-face keyframes supports page namespace layer container property " +
                    "use forward mixin include extend function return if else each for while content plugin",
            )
    }
}
