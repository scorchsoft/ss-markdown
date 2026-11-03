package com.scorchsoft.ssmarkdown.markdown.code

import com.scorchsoft.ssmarkdown.markdown.InlineStyle
import com.scorchsoft.ssmarkdown.markdown.TokenKind

/**
 * YAML: keys, list dashes, comments, document markers, anchors and tags,
 * and values, where an unquoted scalar is a string unless it is a number
 * or one of YAML's literal words (as highlight.js colours it). A block
 * scalar (`key: |`) is carried: following lines indented past the key are
 * its string content. Flow collections (`[a, b]`, `{a: 1}`) colour their
 * quoted strings, numbers and literals only.
 */
internal class YamlLexer : CodeLexer() {
    @Suppress("ReturnCount") // One early answer per kind of line: blank, block scalar, marker, entry.
    override fun lex(
        from: Int,
        state: Int,
    ): Int {
        val i = skipSpaces(from)
        val indent = i - from
        if (CodeState.mode(state) == BLOCK_SCALAR) {
            if (i >= end) return state
            if (indent > CodeState.extra(state)) {
                paint(i, end, TokenKind.CODE_STRING)
                return state
            }
        }
        if (i >= end) return 0
        if (indent == 0 && (matchesAt(i, "---") || matchesAt(i, "..."))) {
            paint(i, end, TokenKind.CODE_META)
            return 0
        }
        return entry(i, from)
    }

    @Suppress("ComplexCondition") // A list dash is `-` then a space or the line's end.
    private fun entry(
        start: Int,
        from: Int,
    ): Int {
        var i = start
        while (i < end && text[i] == '-' && (i + 1 == end || isSpace(text[i + 1]))) {
            paint(i, i + 1, TokenKind.CODE_SYMBOL)
            i = skipSpaces(i + 1)
        }
        if (i >= end) return 0
        if (text[i] == '#') {
            paint(i, end, TokenKind.CODE_COMMENT)
            return 0
        }
        val keyColumn = i - from
        val colon = keyEnd(i)
        if (colon >= 0) {
            paint(i, colon, TokenKind.CODE_ATTRIBUTE)
            i = skipSpaces(colon + 1)
        }
        return value(i, keyColumn)
    }

    /** The `:` that ends a key starting at [i], or -1 when the line has no key there. */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition") // YAML's key rules: quoted, plain, `: ` or `:` at the end.
    private fun keyEnd(i: Int): Int {
        if (text[i] == '"' || text[i] == '\'') {
            val close = stringEnd(i, text[i])
            return if (close < end && text[close] == ':' &&
                (close + 1 == end || isSpace(text[close + 1]))
            ) {
                close
            } else {
                -1
            }
        }
        if (text[i] in FLOW_OR_INDICATOR) return -1
        var j = i
        while (j < end) {
            val c = text[j]
            if (c == ':' && (j + 1 == end || isSpace(text[j + 1]))) return j
            if (c == '#' && j > i && isSpace(text[j - 1])) return -1
            j++
        }
        return -1
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition") // One branch per kind of value.
    private fun value(
        start: Int,
        keyColumn: Int,
    ): Int {
        var i = start
        while (i < end && (text[i] == '&' || text[i] == '*' || text[i] == '!')) {
            var j = i + 1
            while (j < end && !isSpace(text[j])) j++
            paint(i, j, TokenKind.CODE_TYPE)
            i = skipSpaces(j)
        }
        if (i >= end) return 0
        val c = text[i]
        return when {
            c == '#' -> {
                paint(i, end, TokenKind.CODE_COMMENT)
                0
            }

            c == '|' || c == '>' -> {
                commentAfter(i + 1)
                CodeState.pack(BLOCK_SCALAR, extra = minOf(keyColumn, CodeState.MAX_EXTRA))
            }

            c == '[' || c == '{' -> {
                flow(i)
                0
            }

            c == '"' || c == '\'' -> {
                val close = stringEnd(i, c, escapes = c == '"')
                paint(i, close, TokenKind.CODE_STRING)
                commentAfter(close)
                0
            }

            else -> {
                scalar(i)
                0
            }
        }
    }

    /** A plain scalar up to a ` #` comment: a number, a literal word, or a string. */
    private fun scalar(start: Int) {
        var j = start
        while (j < end && !(text[j] == '#' && isSpace(text[j - 1]))) j++
        val comment = j
        while (j > start && isSpace(text[j - 1])) j--
        val kind =
            when {
                LITERALS.contains(text, start, j) -> TokenKind.CODE_LITERAL
                isNumber(start, j) -> TokenKind.CODE_NUMBER
                else -> TokenKind.CODE_STRING
            }
        paint(start, j, kind)
        if (comment < end) paint(comment, end, TokenKind.CODE_COMMENT)
    }

    private fun isNumber(
        start: Int,
        stop: Int,
    ): Boolean {
        if (start >= stop) return false
        val first = if (text[start] == '-' || text[start] == '+') start + 1 else start
        return first < stop && (text[first].isDigitAscii() || text[first] == '.') && numberEnd(first) == stop
    }

    private fun flow(start: Int) {
        var i = start
        while (i < end) {
            val c = text[i]
            i =
                when {
                    c == '"' || c == '\'' -> paintRun(i, stringEnd(i, c, escapes = c == '"'), TokenKind.CODE_STRING)
                    c == '#' && isSpace(text[i - 1]) -> paintRun(i, end, TokenKind.CODE_COMMENT)
                    c.isDigitAscii() && !isNamePart(text[i - 1]) -> paintRun(i, numberEnd(i), TokenKind.CODE_NUMBER)
                    isNameStart(c) -> literalWord(i)
                    else -> i + 1
                }
        }
    }

    private fun literalWord(i: Int): Int {
        var j = i + 1
        while (j < end && isNamePart(text[j])) j++
        if (LITERALS.contains(text, i, j)) paint(i, j, TokenKind.CODE_LITERAL)
        return j
    }

    private fun commentAfter(from: Int) {
        val j = skipSpaces(from)
        if (j < end && text[j] == '#') paint(j, end, TokenKind.CODE_COMMENT)
    }

    private fun paintRun(
        from: Int,
        to: Int,
        kind: TokenKind,
    ): Int {
        paint(from, to, kind)
        return to
    }

    private companion object {
        const val BLOCK_SCALAR = 1
        const val FLOW_OR_INDICATOR = "[{|>&*!%@`"
        val LITERALS = WordTable.of("true false null yes no on off ~", ignoreCase = true)
    }
}

/**
 * INI and TOML: `[section]` headers, `key = value` (and INI's `key: value`),
 * comments, and values: strings, numbers and dates, literal words, and
 * TOML's arrays and inline tables. TOML's `"""` and `'''` strings are
 * carried across lines.
 */
internal class IniLexer(
    /** True for TOML: `#` comments anywhere, multi-line strings, bare-key rules. */
    private val toml: Boolean,
) : CodeLexer() {
    private var mode = 0

    @Suppress("CyclomaticComplexMethod") // One branch per kind of line: comment, section, key.
    override fun lex(
        from: Int,
        state: Int,
    ): Int {
        mode = CodeState.mode(state)
        var i = from
        if (mode != 0) {
            i = multiLine(from, from)
            if (mode != 0) return CodeState.pack(mode)
        }
        i = skipSpaces(i)
        if (i >= end) return 0
        val c = text[i]
        when {
            c == '#' || (c == ';' && !toml) -> {
                paint(i, end, TokenKind.CODE_COMMENT)
            }

            c == '[' -> {
                var j = i
                while (j < end && text[j] != '#') j++
                while (j > i && text[j - 1] != ']') j--
                paint(i, if (j > i) j else end, TokenKind.CODE_SECTION)
                if (j > i) values(j)
            }

            else -> {
                keyValue(i)
            }
        }
        return CodeState.pack(mode)
    }

    @Suppress("ComplexCondition") // `=` always separates; `:` only in INI.
    private fun keyValue(start: Int) {
        var j = start
        while (j < end && text[j] != '=' && !(text[j] == ':' && !toml)) {
            if (text[j] == '"' || text[j] == '\'') j = stringEnd(j, text[j]) - 1
            j++
        }
        if (j >= end) return
        var keyEnd = j
        while (keyEnd > start && isSpace(text[keyEnd - 1])) keyEnd--
        paint(start, keyEnd, TokenKind.CODE_ATTRIBUTE)
        values(j + 1)
    }

    @Suppress("CyclomaticComplexMethod") // One branch per kind of value.
    private fun values(from: Int) {
        var i = from
        while (i < end) {
            val c = text[i]
            i =
                when {
                    toml && (matchesAt(i, "\"\"\"") || matchesAt(i, "'''")) -> {
                        mode = if (c == '"') BASIC else LITERAL
                        multiLine(i + TRIPLE, i)
                    }

                    c == '"' || c == '\'' -> {
                        paintRun(i, stringEnd(i, c, escapes = c == '"'), TokenKind.CODE_STRING)
                    }

                    c == '#' || (c == ';' && !toml && i > 0 && isSpace(text[i - 1])) -> {
                        paintRun(i, end, TokenKind.CODE_COMMENT)
                    }

                    (c.isDigitAscii() || ((c == '-' || c == '+') && i + 1 < end && text[i + 1].isDigitAscii())) &&
                        (i == 0 || !isNamePart(text[i - 1])) -> {
                        paintRun(i, dateOrNumberEnd(i + 1), TokenKind.CODE_NUMBER)
                    }

                    isNameStart(c) -> {
                        word(i)
                    }

                    else -> {
                        i + 1
                    }
                }
        }
    }

    /** Numbers, dates and times all run on through digits, `-`, `:`, `.`, `T`, `Z` and `_`. */
    private fun dateOrNumberEnd(from: Int): Int {
        var j = from
        while (j < end && (isNamePart(text[j]) || text[j] in NUMBER_PUNCTUATION)) j++
        return j
    }

    private fun word(i: Int): Int {
        var j = i + 1
        while (j < end && (isNamePart(text[j]) || text[j] == '-')) j++
        val next = skipSpaces(j)
        when {
            LITERALS.contains(text, i, j) -> paint(i, j, TokenKind.CODE_LITERAL)
            toml && next < end && text[next] == '=' -> paint(i, j, TokenKind.CODE_ATTRIBUTE)
        }
        return j
    }

    private fun multiLine(
        from: Int,
        paintFrom: Int,
    ): Int {
        val close = if (mode == BASIC) "\"\"\"" else "'''"
        var j = from
        while (j < end) {
            if (text[j] == '\\' && mode == BASIC) {
                j += 2
                continue
            }
            if (matchesAt(j, close)) {
                mode = 0
                paint(paintFrom, j + TRIPLE, TokenKind.CODE_STRING)
                return j + TRIPLE
            }
            j++
        }
        paint(paintFrom, end, TokenKind.CODE_STRING)
        return end
    }

    private fun paintRun(
        from: Int,
        to: Int,
        kind: TokenKind,
    ): Int {
        paint(from, to, kind)
        return to
    }

    private companion object {
        const val BASIC = 1
        const val LITERAL = 2
        const val TRIPLE = 3
        const val NUMBER_PUNCTUATION = "-:.+"
        val LITERALS = WordTable.of("true false yes no on off inf nan", ignoreCase = true)
    }
}

/**
 * Unified and context diffs, line by line: additions, deletions, hunk
 * headers (meta) and file headers (comment), as highlight.js classes them.
 */
internal class DiffLexer : CodeLexer() {
    override fun lex(
        from: Int,
        state: Int,
    ): Int {
        if (from >= end) return 0
        val kind =
            when {
                HEADERS.any { matchesAt(from, it) } -> TokenKind.CODE_COMMENT
                matchesAt(from, "@@") || matchesAt(from, "***") -> TokenKind.CODE_META
                text[from] == '+' || text[from] == '>' -> TokenKind.CODE_ADDITION
                text[from] == '-' || text[from] == '<' -> TokenKind.CODE_DELETION
                else -> null
            }
        if (kind != null) paint(from, end, kind)
        return 0
    }

    private companion object {
        val HEADERS = arrayOf("+++", "---", "diff ", "index ", "Index: ", "===", "Only in ")
    }
}

/**
 * Markdown inside a ```` ```md ```` block, kept flat ("nothing nested"):
 * headings, list bullets, quotes, inline code, bold and italic, and link
 * text. A fence line inside it is just code text; what it fences is not
 * highlighted, and nothing is carried between lines.
 */
internal class MarkdownCodeLexer : CodeLexer() {
    @Suppress("ReturnCount") // A heading, quote or fence line is whole; anything else goes inline.
    override fun lex(
        from: Int,
        state: Int,
    ): Int {
        var i = skipSpaces(from)
        if (i >= end) return 0
        val c = text[i]
        when {
            c == '#' && heading(i) -> {
                paint(i, end, TokenKind.CODE_SECTION)
                return 0
            }

            c == '>' -> {
                paint(i, end, TokenKind.CODE_COMMENT)
                return 0
            }

            matchesAt(i, "```") || matchesAt(i, "~~~") -> {
                paint(i, end, TokenKind.CODE_STRING)
                return 0
            }

            else -> {
                i = bullet(i)
            }
        }
        inline(i)
        return 0
    }

    private fun heading(i: Int): Boolean {
        var j = i
        while (j < end && text[j] == '#') j++
        return j - i <= MAX_HEADING && (j == end || isSpace(text[j]))
    }

    @Suppress("ComplexCondition") // `-`, `*`, `+` or `1.`/`1)`, then a space.
    private fun bullet(i: Int): Int {
        var j = i
        if (text[j] == '-' || text[j] == '*' || text[j] == '+') {
            j++
        } else {
            while (j < end && text[j].isDigitAscii()) j++
            if (j == i || j >= end || (text[j] != '.' && text[j] != ')')) return i
            j++
        }
        if (j < end && !isSpace(text[j])) return i
        paint(i, j, TokenKind.CODE_SYMBOL)
        return j
    }

    private fun inline(from: Int) {
        var i = from
        while (i < end) {
            val c = text[i]
            i =
                when {
                    c == '`' -> {
                        span(i, "`", TokenKind.CODE_STRING, 0)
                    }

                    matchesAt(i, "**") || matchesAt(i, "__") -> {
                        span(i, if (c == '*') "**" else "__", null, InlineStyle.STRONG)
                    }

                    (c == '*' || c == '_') && i + 1 < end && !isSpace(text[i + 1]) -> {
                        span(i, if (c == '*') "*" else "_", null, InlineStyle.EMPHASIS)
                    }

                    c == '[' -> {
                        linkText(i)
                    }

                    else -> {
                        i + 1
                    }
                }
        }
    }

    /** From [i] to the next [marker]: painted [kind] or styled [style]; unmatched, the marker is plain. */
    private fun span(
        i: Int,
        marker: String,
        kind: TokenKind?,
        style: Int,
    ): Int {
        var j = i + marker.length
        while (j < end && !matchesAt(j, marker)) j++
        if (j >= end || j == i + marker.length) return i + marker.length
        val close = j + marker.length
        if (kind != null) paint(i, close, kind) else out.addStyle(i, close, style)
        return close
    }

    private fun linkText(i: Int): Int {
        var j = i + 1
        while (j < end && text[j] != ']') j++
        if (j + 1 >= end || text[j + 1] != '(') return i + 1
        paint(i, j + 1, TokenKind.CODE_STRING)
        return j + 1
    }

    private companion object {
        const val MAX_HEADING = 6
    }
}
