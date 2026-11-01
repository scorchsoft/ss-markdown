// Recognisers bail out the moment a construct fails to match, as CommonMark's
// own reference parsers do; one exit per function would bury the grammar in flags.
@file:Suppress("ReturnCount")

package com.scorchsoft.ssmarkdown.markdown

/**
 * Recognisers for the starts of CommonMark (+ GFM) block constructs, each a
 * pure function of `text[from, end)`. `end` is the tokenised prefix of the
 * line, so no recogniser ever reads past [LineTokenizer.MAX_TOKENIZED_COLUMNS].
 *
 * They answer "does a block start here?" and nothing else; the tokenizer
 * decides whether that block may start in the current state (a reference
 * definition cannot interrupt a paragraph, and so on).
 */
@Suppress("TooManyFunctions") // One small recogniser per block construct; splitting them up would scatter the grammar.
internal object BlockSyntax {
    const val MAX_INDENT = 3
    const val MIN_FENCE = 3
    const val MAX_HEADING_LEVEL = 6
    private const val MAX_ORDINAL_DIGITS = 9
    private const val MAX_LABEL_LENGTH = 999
    private const val BULLETS = "-+*"

    fun isSpace(c: Char): Boolean = c == ' ' || c == '\t'

    fun isBlank(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Boolean {
        for (i in from until end) if (!isSpace(text[i])) return false
        return true
    }

    /** Index of the first non-space character at or after [from], or [end]. */
    fun skipSpaces(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        var i = from
        while (i < end && isSpace(text[i])) i++
        return i
    }

    /** Index just past the last non-space character before [end], but not before [from]. */
    fun trimEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        var i = end
        while (i > from && isSpace(text[i - 1])) i--
        return i
    }

    /** `***`, `- - -`, `___`: three or more of one of `*-_`, with only spaces between. */
    fun isThematicBreak(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Boolean {
        if (from >= end) return false
        val c = text[from]
        if (c != '*' && c != '-' && c != '_') return false
        var count = 0
        for (i in from until end) {
            val ch = text[i]
            if (ch == c) {
                count++
            } else if (!isSpace(ch)) {
                return false
            }
        }
        return count >= MIN_FENCE
    }

    /** The level of an ATX heading starting at [from] (1..6), or 0 if there is none. */
    fun atxLevel(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        var i = from
        while (i < end && text[i] == '#') i++
        val level = i - from
        val valid = level in 1..MAX_HEADING_LEVEL && (i == end || isSpace(text[i]))
        return if (valid) level else 0
    }

    /** Where an ATX heading's optional closing `#` run starts, or [contentEnd] if it has none. */
    fun atxClosingStart(
        text: CharSequence,
        contentStart: Int,
        contentEnd: Int,
    ): Int {
        var i = contentEnd
        while (i > contentStart && text[i - 1] == '#') i--
        val closes = i < contentEnd && (i == contentStart || isSpace(text[i - 1]))
        return if (closes) i else contentEnd
    }

    /** The length of the fence run at [from] (3+ backticks or tildes), or 0. Backtick info may not hold a backtick. */
    fun fenceLength(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        if (from >= end) return 0
        val c = text[from]
        if (c != '`' && c != '~') return 0
        var i = from
        while (i < end && text[i] == c) i++
        val length = i - from
        if (length < MIN_FENCE) return 0
        if (c == '`') for (j in i until end) if (text[j] == '`') return 0
        return length
    }

    /** True if [from] closes a fence of [char] x [length]: at least as long, then only spaces. */
    fun closesFence(
        text: CharSequence,
        from: Int,
        end: Int,
        char: Char,
        length: Int,
    ): Boolean {
        var i = from
        while (i < end && text[i] == char) i++
        return i - from >= length && isBlank(text, i, end)
    }

    /** 1 for `===`, 2 for `---`, 0 otherwise. Trailing spaces allowed, inner spaces not. */
    fun setextLevel(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        if (from >= end) return 0
        val c = text[from]
        if (c != '=' && c != '-') return 0
        var i = from
        while (i < end && text[i] == c) i++
        return when {
            !isBlank(text, i, end) -> 0
            c == '=' -> 1
            else -> 2
        }
    }

    /**
     * The width of a list marker at [from] (`-`, `+`, `*`, `1.`, `1)`), or 0.
     * [ListMarker] says whether it is ordered, starts at 1, and is empty.
     */
    fun listMarker(
        text: CharSequence,
        from: Int,
        end: Int,
        out: ListMarker,
    ): Int {
        if (from >= end) return 0
        val c = text[from]
        out.ordered = c !in BULLETS
        val markerEnd = if (out.ordered) ordinalEnd(text, from, end, out) else from + 1
        val valid = markerEnd > from && (markerEnd == end || isSpace(text[markerEnd]))
        out.empty = valid && isBlank(text, markerEnd, end)
        return if (valid) markerEnd - from else 0
    }

    /** End of `1.` or `12)` at [from], or [from] if there is none. */
    private fun ordinalEnd(
        text: CharSequence,
        from: Int,
        end: Int,
        out: ListMarker,
    ): Int {
        var i = from
        while (i < end && i - from < MAX_ORDINAL_DIGITS && text[i] in '0'..'9') i++
        val delimited = i > from && i < end && (text[i] == '.' || text[i] == ')')
        out.startsAtOne = i - from == 1 && text[from] == '1'
        return if (delimited) i + 1 else from
    }

    /** Facts about the last list marker [listMarker] matched. */
    class ListMarker {
        var ordered: Boolean = false
        var startsAtOne: Boolean = false
        var empty: Boolean = false
    }

    /** Cells in a table delimiter row (`| :--- | ---: |`), or 0 if `text[from, end)` is not one. */
    fun delimiterRowCells(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        var i = from
        val last = trimEnd(text, from, end)
        if (i < last && text[i] == '|') i++
        val stop = if (last > i && text[last - 1] == '|') last - 1 else last
        var cells = 0
        while (i <= stop) {
            val cellEnd = indexOf(text, '|', i, stop).let { if (it < 0) stop else it }
            if (!isDelimiterCell(text, skipSpaces(text, i, cellEnd), trimEnd(text, i, cellEnd))) return 0
            cells++
            i = cellEnd + 1
        }
        return cells
    }

    private fun isDelimiterCell(
        text: CharSequence,
        from: Int,
        to: Int,
    ): Boolean {
        var i = from
        if (i < to && text[i] == ':') i++
        val dashesFrom = i
        while (i < to && text[i] == '-') i++
        if (i == dashesFrom) return false
        if (i < to && text[i] == ':') i++
        return i == to
    }

    fun indexOf(
        text: CharSequence,
        c: Char,
        from: Int,
        end: Int,
    ): Int {
        for (i in from until end) if (text[i] == c) return i
        return -1
    }

    /** True if `text[from, end)` contains [needle], ignoring ASCII case. */
    fun containsIgnoreCase(
        text: CharSequence,
        needle: String,
        from: Int,
        end: Int,
    ): Boolean {
        for (i in from..end - needle.length) if (regionMatches(text, i, needle)) return true
        return false
    }

    fun regionMatches(
        text: CharSequence,
        at: Int,
        needle: String,
    ): Boolean {
        if (at < 0 || at + needle.length > text.length) return false
        for (j in needle.indices) if (text[at + j].lowercaseChar() != needle[j]) return false
        return true
    }

    /**
     * Parses `[label]: destination "title"` at [from] into [out]: the positions
     * of each part, or false if this is not a reference definition. A label
     * starting with `^` is a footnote definition, whose rest is free text.
     */
    fun referenceDefinition(
        text: CharSequence,
        from: Int,
        end: Int,
        out: Definition,
    ): Boolean {
        val close = labelEnd(text, from, end)
        if (close < 0 || close + 1 >= end || text[close + 1] != ':') return false
        out.labelStart = from + 1
        out.labelEnd = close
        out.isFootnote = text[from + 1] == '^'
        if (out.isFootnote) return close > from + 2
        val destStart = skipSpaces(text, close + 2, end)
        val destEnd = InlineSyntax.destinationEnd(text, destStart, end)
        if (destEnd <= destStart) return false
        out.destStart = destStart
        out.destEnd = destEnd
        val titleStart = skipSpaces(text, destEnd, end)
        val titleEnd = if (titleStart > destEnd) InlineSyntax.titleEnd(text, titleStart, end) else -1
        out.titleStart = if (titleEnd > titleStart) titleStart else -1
        out.titleEnd = titleEnd
        val tail = if (titleEnd > titleStart) titleEnd else destEnd
        return isBlank(text, tail, end)
    }

    /** The `]` closing a link label opened at [from], or -1: no nested brackets, not blank, at most 999 chars. */
    fun labelEnd(
        text: CharSequence,
        from: Int,
        end: Int,
    ): Int {
        if (from >= end || text[from] != '[') return -1
        var i = from + 1
        while (i < end && i - from <= MAX_LABEL_LENGTH) {
            when (text[i]) {
                '\\' -> i++
                '[' -> return -1
                ']' -> return if (isBlank(text, from + 1, i)) -1 else i
            }
            i++
        }
        return -1
    }

    /** Positions found by [referenceDefinition]. */
    class Definition {
        var labelStart: Int = 0
        var labelEnd: Int = 0
        var destStart: Int = 0
        var destEnd: Int = 0
        var titleStart: Int = -1
        var titleEnd: Int = -1
        var isFootnote: Boolean = false
    }

    /**
     * CommonMark's link-label normalisation (trim, collapse whitespace, case
     * fold), so `[Foo  Bar]` and `[foo bar]` index under the same key.
     */
    fun normalizeLabel(
        text: CharSequence,
        from: Int,
        to: Int,
    ): String {
        val out = CollapsedText()
        for (i in from until to) out.append(text[i])
        return out.toString().uppercase().lowercase()
    }
}
