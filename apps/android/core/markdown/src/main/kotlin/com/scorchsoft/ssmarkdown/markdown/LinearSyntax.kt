package com.scorchsoft.ssmarkdown.markdown

/**
 * Line tests written as scans rather than regexes. Java's regex engine
 * recurses once per repetition of a group under `*` or `{n,}`, so a pattern
 * like `(\|\s*:?-+:?\s*)*` overflowed the stack on a table row of a few
 * thousand cells (MalformedMarkdownTest, "huge table row"). Each test here
 * is linear and allocation-free, and matches exactly what its regex did.
 */
internal object LinearSyntax {
    /** `\s` in a Java regex: space, tab, line feed, vertical tab, form feed, carriage return. */
    fun isRegexSpace(c: Char): Boolean = c == ' ' || c in '\t'..'\r'

    /** A space or a tab. */
    fun isSpaceOrTab(c: Char): Boolean = c == ' ' || c == '\t'

    /**
     * A GFM table delimiter row (`| --- | :-: |`): one or more cells of
     * `:?-+:?` separated by `|`, with optional outer pipes, and only [blank]
     * characters around the cells and pipes.
     */
    fun isDelimiterRow(
        line: CharSequence,
        blank: (Char) -> Boolean,
    ): Boolean {
        var start = 0
        var end = line.length
        while (start < end && blank(line[start])) start++
        while (end > start && blank(line[end - 1])) end--
        if (start < end && line[start] == '|') start++
        if (end > start && line[end - 1] == '|') end--
        var cellStart = start
        while (true) {
            var cellEnd = cellStart
            while (cellEnd < end && line[cellEnd] != '|') cellEnd++
            if (!isDelimiterCell(line, cellStart, cellEnd, blank)) return false
            if (cellEnd >= end) return true
            cellStart = cellEnd + 1
        }
    }

    /** `[start, end)` of [line] is `:?-+:?` with only [blank] around it. */
    private fun isDelimiterCell(
        line: CharSequence,
        start: Int,
        end: Int,
        blank: (Char) -> Boolean,
    ): Boolean {
        var i = start
        var j = end
        while (i < j && blank(line[i])) i++
        while (j > i && blank(line[j - 1])) j--
        if (i < j && line[i] == ':') i++
        if (j > i && line[j - 1] == ':') j--
        if (i >= j) return false
        for (k in i until j) if (line[k] != '-') return false
        return true
    }

    /**
     * A thematic break: up to three spaces, then three or more of one of
     * `*`, `-` or `_`, with only spaces and tabs between and after them.
     */
    fun isThematicBreak(line: CharSequence): Boolean {
        var i = 0
        while (i < line.length && i < MAX_INDENT && line[i] == ' ') i++
        val mark = line.getOrNull(i) ?: return false
        if (mark != '*' && mark != '-' && mark != '_') return false
        return isRunOf(line, i, mark, MIN_RULE_MARKS)
    }

    /**
     * From [from] to the end, [line] holds only [mark], spaces and tabs,
     * with [mark] at [from] and at least [min] of them in all.
     */
    fun isRunOf(
        line: CharSequence,
        from: Int,
        mark: Char,
        min: Int,
    ): Boolean {
        if (line.getOrNull(from) != mark) return false
        var count = 0
        for (k in from until line.length) {
            val c = line[k]
            if (c == mark) {
                count++
            } else if (!isSpaceOrTab(c)) {
                return false
            }
        }
        return count >= min
    }

    private const val MAX_INDENT = 3
    private const val MIN_RULE_MARKS = 3
}
