package com.scorchsoft.ssmarkdown.markdown.tidy

/**
 * What one line can start, read the way markdown-it reads it (CommonMark,
 * GFM tables and the Preview's plugins). Each function is a port of the
 * matching markdown-it block rule's opening test, so Tidy and Preview agree
 * on where blocks begin. Columns count a tab to the next multiple of four,
 * as markdown-it does.
 */
@Suppress("TooManyFunctions") // One small test per block rule; together they are the block grammar.
internal object TidyGrammar {
    /** Indentation that makes a line indented code (or a paragraph continuation). */
    const val CODE_INDENT = 4

    private const val TAB = 4
    private const val MIN_FENCE = 3
    private const val MAX_DIGITS = 9
    private const val MAX_HEADING = 6

    /** Above this length a line starting with `<` is not matched against the tag pattern; it counts as unsure. */
    private const val MAX_TAG_LINE = 4_096

    fun isBlank(line: String): Boolean = line.all { it == ' ' || it == '\t' }

    /** The column of the first character that is not a space or tab. */
    fun indent(line: String): Int {
        var col = 0
        for (c in line) {
            col =
                when (c) {
                    ' ' -> col + 1
                    '\t' -> col + TAB - col % TAB
                    else -> return col
                }
        }
        return col
    }

    /** Index of the first character that is not a space or tab. */
    fun firstText(line: String): Int {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return i
    }

    fun hasLeadingTab(line: String): Boolean = line.subSequence(0, firstText(line)).contains('\t')

    /** True for a line that starts with a tab or four spaces: code, or a continuation; its spacing is never touched. */
    fun isDeep(line: String): Boolean = line.startsWith('\t') || line.startsWith("    ")

    /** A fence a line opens: its character, length and indent. */
    data class Fence(
        val char: Char,
        val length: Int,
        val indent: Int,
    ) {
        /** The line that closes it, for a fence left open. */
        val closer: String get() = " ".repeat(indent) + char.toString().repeat(length)
    }

    fun fenceOpen(line: String): Fence? {
        if (indent(line) >= CODE_INDENT) return null
        val p = firstText(line)
        val c = line.getOrNull(p)
        if (c != '`' && c != '~') return null
        val run = runLength(line, p, c)
        if (run < MIN_FENCE) return null
        // A backtick fence's info string may not hold a backtick ("``` a`b" is inline code).
        if (c == '`' && line.indexOf('`', p + run) >= 0) return null
        return Fence(c, run, p)
    }

    fun closes(
        line: String,
        fence: Fence,
    ): Boolean {
        if (indent(line) >= CODE_INDENT) return false
        val p = firstText(line)
        if (line.getOrNull(p) != fence.char) return false
        val run = runLength(line, p, fence.char)
        return run >= fence.length && isBlank(line.substring(p + run))
    }

    private fun runLength(
        line: String,
        from: Int,
        c: Char,
    ): Int {
        var i = from
        while (i < line.length && line[i] == c) i++
        return i - from
    }

    fun isQuote(line: String): Boolean = indent(line) < CODE_INDENT && line.getOrNull(firstText(line)) == '>'

    fun isMathStart(line: String): Boolean = indent(line) < CODE_INDENT && line.startsWith("$$", firstText(line))

    /** A `$$` line that also closes its block, as the Preview's maths rule reads it. */
    fun isMathOneLine(line: String): Boolean = line.substring(firstText(line) + 2).trim().endsWith("$$")

    fun closesMath(line: String): Boolean = line.trim().endsWith("$$")

    fun isHr(line: String): Boolean {
        if (indent(line) >= CODE_INDENT) return false
        val p = firstText(line)
        val marker = line.getOrNull(p)
        if (marker != '*' && marker != '-' && marker != '_') return false
        var count = 0
        for (i in p until line.length) {
            when (line[i]) {
                marker -> count++
                ' ', '\t' -> Unit
                else -> return false
            }
        }
        return count >= MIN_FENCE
    }

    /** A `===` or `---` line, which turns the paragraph above it into a heading. */
    fun isSetextUnderline(line: String): Boolean {
        if (indent(line) >= CODE_INDENT) return false
        val p = firstText(line)
        val c = line.getOrNull(p)
        if (c != '=' && c != '-') return false
        return isBlank(line.substring(p + runLength(line, p, c)))
    }

    fun isAtxHeading(line: String): Boolean {
        if (indent(line) >= CODE_INDENT) return false
        val p = firstText(line)
        val run = runLength(line, p, '#')
        if (run == 0 || run > MAX_HEADING) return false
        val after = line.getOrNull(p + run)
        return after == null || after == ' ' || after == '\t'
    }

    /** A `:` or `~` definition-list marker (markdown-it-deflist). */
    fun isDefinitionMarker(line: String): Boolean {
        if (indent(line) >= CODE_INDENT) return false
        val p = firstText(line)
        val c = line.getOrNull(p)
        if (c != ':' && c != '~') return false
        val after = line.getOrNull(p + 1)
        return after == null || after == ' ' || after == '\t'
    }

    private val FOOTNOTE_OR_ABBREVIATION = Regex("^ {0,3}(?:\\[\\^[^\\]\\s]+\\]|\\*\\[[^\\]]+\\]):")

    private val FOOTNOTE = Regex("^ {0,3}\\[\\^[^\\]\\s]+\\]:")

    /** A footnote definition, `[^1]: text`, which holds the indented lines after it. */
    fun isFootnote(line: String): Boolean = FOOTNOTE.containsMatchIn(line)

    /**
     * True when a run of backticks on [line] finds no run of the same length
     * after it: a code span that may carry on to the next line, keeping that
     * line break's spaces as code. A backslash-escaped backtick opens nothing.
     */
    fun hasOpenCodeSpan(line: String): Boolean {
        val runs = mutableListOf<Int>()
        var i = 0
        while (i < line.length) {
            when {
                line[i] == '\\' && line.getOrNull(i + 1) == '`' -> {
                    i += 2
                }

                line[i] == '`' -> {
                    val run = runLength(line, i, '`')
                    runs += run
                    i += run
                }

                else -> {
                    i++
                }
            }
        }
        var k = 0
        while (k < runs.size) {
            val close = (k + 1 until runs.size).firstOrNull { runs[it] == runs[k] } ?: return true
            k = close + 1
        }
        return false
    }

    /** A footnote (`[^1]:`) or abbreviation (`*[HTML]:`) definition, which may interrupt a paragraph. */
    fun isFootnoteOrAbbreviation(line: String): Boolean = FOOTNOTE_OR_ABBREVIATION.containsMatchIn(line)

    /** A line that may be a link, footnote or abbreviation definition, after which a paragraph may not be open. */
    fun isDefinitionLike(line: String): Boolean {
        val p = firstText(line)
        return (line.startsWith("[", p) || line.startsWith("*[", p)) && line.contains("]:")
    }

    /** A list item marker at the start of [line], at any indent; the caller limits the indent. */
    data class Marker(
        /** Column of the marker. */
        val indent: Int,
        /** Index just past the marker. */
        val end: Int,
        /** `-`, `+` or `*` for bullets, `.` or `)` for numbered items. */
        val kind: Char,
        val ordered: Boolean,
        val number: Long,
        /** The digits' range, for a numbered item. */
        val numberStart: Int,
        val empty: Boolean,
        /** Spaces between the marker and the text. */
        val gap: Int,
        /** Column where the item's content starts, as markdown-it works it out. */
        val contentCol: Int,
        /** A tab between the marker and the text. */
        val tabAfter: Boolean,
    ) {
        /** True when the item may start a list in the middle of a paragraph. */
        val canInterrupt: Boolean get() = !empty && (!ordered || number == 1L)

        val digits: Int get() = if (ordered) end - 1 - numberStart else 0

        /** Empty, tab-separated, or followed by indented code: items Tidy leaves alone. */
        val isOdd: Boolean get() = empty || tabAfter || gap > CODE_INDENT
    }

    fun listMarker(line: String): Marker? {
        val p = firstText(line)
        val c = line.getOrNull(p) ?: return null
        val end = if (c == '-' || c == '+' || c == '*') p + 1 else orderedEnd(line, p)
        if (end < 0) return null
        val after = line.getOrNull(end)
        if (after != null && after != ' ' && after != '\t') return null
        return marker(line, p, end, if (end == p + 1) 0 else line.substring(p, end - 1).toLong())
    }

    /** Index past `12.` or `3)` at [p], or -1: one to nine digits, then the delimiter. */
    private fun orderedEnd(
        line: String,
        p: Int,
    ): Int {
        var i = p
        while (i < line.length && line[i].isAsciiDigit()) i++
        val delimited = line.getOrNull(i)?.let { it == '.' || it == ')' } == true
        return if (i == p || i - p > MAX_DIGITS || !delimited) -1 else i + 1
    }

    private fun marker(
        line: String,
        p: Int,
        end: Int,
        number: Long,
    ): Marker {
        val ordered = number > 0 || line[p].isAsciiDigit()
        var i = end
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        val empty = i >= line.length
        val gap = i - end
        val col = indent(line)
        val width = end - p
        val after = if (empty || gap > CODE_INDENT) 1 else gap
        return Marker(
            indent = col,
            end = end,
            kind = if (ordered) line[end - 1] else line[p],
            ordered = ordered,
            number = number,
            numberStart = p,
            empty = empty,
            gap = gap,
            contentCol = col + width + after,
            tabAfter = line.subSequence(end, i).contains('\t'),
        )
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    /**
     * The HTML block a line starts (CommonMark types 1 to 7), 0 for none, or
     * [HTML_UNSURE] for a line too long to match safely.
     */
    fun htmlType(line: String): Int {
        if (indent(line) >= CODE_INDENT) return 0
        val text = line.substring(firstText(line))
        if (!text.startsWith("<")) return 0
        if (text.length > MAX_TAG_LINE) return HTML_UNSURE
        val type = HTML_STARTS.indexOfFirst { it.containsMatchIn(text) }
        return if (type < 0) 0 else type + 1
    }

    /** True when [line] ends an HTML block of [type] (1 to 5); each is searched for on the whole line. */
    fun endsHtml(
        type: Int,
        line: String,
    ): Boolean = HTML_ENDS[type - 1].containsMatchIn(line.substring(firstText(line)))

    const val HTML_UNSURE = -1

    /** Types 1 to 5 run to their end marker, across blank lines. */
    const val HTML_RAW_LAST = 5

    /** Type 6, a block-level tag, ends at a blank line and may interrupt a paragraph. */
    const val HTML_BLOCK_TAG = 6

    private const val BLOCK_NAMES =
        "address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|dialog|dir|" +
            "div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h1|h2|h3|h4|h5|h6|head|header|hr|" +
            "html|iframe|legend|li|link|main|menu|menuitem|nav|noframes|ol|optgroup|option|p|param|search|" +
            "section|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul"

    private const val ATTR_NAME = "[a-zA-Z_:][a-zA-Z0-9:._-]*"
    private const val ATTR_VALUE = "(?:[^\"'=<>`\\x00-\\x20]+|'[^']*'|\"[^\"]*\")"
    private const val ATTRIBUTE = "(?:\\s+$ATTR_NAME(?:\\s*=\\s*$ATTR_VALUE)?)"
    private const val OPEN_TAG = "<[A-Za-z][A-Za-z0-9\\-]*$ATTRIBUTE*\\s*/?>"
    private const val CLOSE_TAG = "</[A-Za-z][A-Za-z0-9\\-]*\\s*>"

    private val HTML_STARTS =
        listOf(
            Regex("^<(script|pre|style|textarea)(?=(\\s|>|$))", RegexOption.IGNORE_CASE),
            Regex("^<!--"),
            Regex("^<\\?"),
            Regex("^<![A-Z]"),
            Regex("^<!\\[CDATA\\["),
            Regex("^</?($BLOCK_NAMES)(?=(\\s|/?>|$))", RegexOption.IGNORE_CASE),
            Regex("^(?:$OPEN_TAG|$CLOSE_TAG)\\s*$"),
        )

    private val HTML_ENDS =
        listOf(
            Regex("</(script|pre|style|textarea)>", RegexOption.IGNORE_CASE),
            Regex("-->"),
            Regex("\\?>"),
            Regex(">"),
            Regex("\\]\\]>"),
        )

    /** A paragraph-ending line: one a lazy continuation line can never be. */
    fun interrupts(line: String): Boolean = startsBlock(line) || startsContainer(line)

    private fun startsBlock(line: String): Boolean =
        fenceOpen(line) != null || isMathStart(line) || isHr(line) || isAtxHeading(line)

    private fun startsContainer(line: String): Boolean =
        isQuote(line) || htmlType(line) in 1..HTML_BLOCK_TAG ||
            (indent(line) < CODE_INDENT && listMarker(line)?.canInterrupt == true)

    /**
     * A line that is plain paragraph text wherever it sits: nothing a block
     * could start with, so whether it continues a paragraph never depends
     * on what is above it.
     */
    fun isPlain(line: String): Boolean {
        val p = firstText(line)
        val c = line.getOrNull(p) ?: return false
        if (c in "#>=-*+_|<:~`$" || line.contains('|')) return false
        // A footnote or abbreviation ends a paragraph but not a list or quote: not plain either.
        return listMarker(line) == null && !line.contains("```") && !line.contains("~~~") &&
            !isFootnoteOrAbbreviation(line)
    }

    /** True for text that may hold a fence, maths or raw HTML: the things that run across blank lines. */
    fun mayRunAcrossBlanks(line: String): Boolean =
        line.contains("```") || line.contains("~~~") || line.contains("$$") ||
            RAW_HTML_OPENERS.any { line.contains(it, ignoreCase = true) }

    private val RAW_HTML_OPENERS = listOf("<!", "<?", "<script", "<pre", "<style", "<textarea")

    /** Header and delimiter rows of a GFM table start at [i], as markdown-it's table rule tests them. */
    fun isTableStart(
        lines: List<String>,
        i: Int,
    ): Boolean {
        if (i + 1 >= lines.size) return false
        val header = lines[i]
        val delimiter = lines[i + 1]
        if (indent(header) >= CODE_INDENT || indent(delimiter) >= CODE_INDENT || !header.contains('|')) return false
        val aligns = delimiterColumns(delimiter) ?: return false
        val cells = escapedSplit(header.trim()).toMutableList()
        if (cells.firstOrNull() == "") cells.removeAt(0)
        if (cells.lastOrNull() == "") cells.removeAt(cells.size - 1)
        return cells.isNotEmpty() && cells.size == aligns
    }

    private val DELIMITER_CELL = Regex("^:?-+:?$")

    /** The number of columns a delimiter row has, or null when the line is not one. */
    private fun delimiterColumns(line: String): Int? {
        val row = line.substring(firstText(line))
        if (!isDelimiterShape(row)) return null
        val columns = row.split('|')
        var count = 0
        for ((n, column) in columns.withIndex()) {
            val t = column.trim()
            val edge = n == 0 || n == columns.size - 1
            when {
                t.isEmpty() && edge -> Unit
                DELIMITER_CELL.matches(t) -> count++
                else -> return null
            }
        }
        return count
    }

    /** markdown-it's quick test: `|`, `-` or `:` first, and only those and spaces; `- ` is a list item. */
    private fun isDelimiterShape(row: String): Boolean {
        if (row.length < 2 || row[0] !in "|-:" || row[1] !in "|-: \t") return false
        if (row[0] == '-' && (row[1] == ' ' || row[1] == '\t')) return false
        return row.all { it in "|-: \t" }
    }

    /** markdown-it's split of a table row on pipes that are not escaped. */
    private fun escapedSplit(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in text) {
            if (c == '|' && !escaped) {
                out += current.toString()
                current.setLength(0)
            } else if (c == '|') {
                current.setLength(current.length - 1)
                current.append(c)
            } else {
                current.append(c)
            }
            escaped = c == '\\'
        }
        out += current.toString()
        return out
    }

    /** A line inside a table's body ends it, as markdown-it's table terminators do. */
    fun endsTable(line: String): Boolean =
        isBlank(line) || indent(line) >= CODE_INDENT || fenceOpen(line) != null || isQuote(line) ||
            isHr(line) || listMarker(line) != null || htmlType(line) in 1..HTML_BLOCK_TAG ||
            isAtxHeading(line) || isMathStart(line)

    /** markdown-it-front-matter opens on a first line of three or more dashes at column 0. */
    fun frontMatterDashes(line: String): Int = if (line.startsWith("---")) runLength(line, 0, '-') else 0

    fun closesFrontMatter(
        line: String,
        dashes: Int,
    ): Boolean {
        if (indent(line) >= CODE_INDENT) return false
        val p = firstText(line)
        val run = runLength(line, p, '-')
        return run > 0 && run >= dashes && line.substring(p + run).all { it == ' ' || it == '\t' }
    }
}
