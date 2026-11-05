package com.scorchsoft.ssmarkdown.markdown.tidy

/** What one line of the document is, as far as Tidy needs to know. */
internal enum class LineClass {
    /** A blank line Tidy may remove or empty. */
    BLANK,

    /** A blank line inside code, maths, HTML or a region left alone: never touched. */
    FROZEN_BLANK,

    /** A line of a top-level paragraph. */
    TEXT,

    /** An ATX heading at the top level. */
    HEADING,

    /** The `===` or `---` under a setext heading. */
    SETEXT,

    /** A thematic break. */
    BREAK,

    /** The opening fence of a top-level code block. */
    FENCE_OPEN,

    /** The closing fence of a top-level code block. */
    FENCE_CLOSE,

    /** Code, maths, HTML, front matter, a table, or anything Tidy is unsure of: never touched. */
    FROZEN,

    /** A line of a list Tidy understands. */
    LIST,

    /** A line of a block quote Tidy understands. */
    QUOTE,
    ;

    val isBlank: Boolean get() = this == BLANK || this == FROZEN_BLANK
}

/** Where a quote that starts at a line ends, and how sure Tidy is of it. */
internal object QuoteRegion {
    /** The quote starting at [start]: the index after its last line, and its kind. */
    fun walk(
        lines: List<String>,
        start: Int,
    ): Pair<Int, RegionKind> {
        var kind = RegionKind.SIMPLE
        var j = start
        while (j < lines.size && kind != RegionKind.UNSURE && continues(lines[j])) {
            kind = worse(kind, kindOf(lines[j]))
            j++
        }
        return j to kind
    }

    private fun worse(
        a: RegionKind,
        b: RegionKind,
    ): RegionKind = if (b.ordinal > a.ordinal) b else a

    /** False for the line that ends the quote: a blank line, or one that interrupts its paragraph. */
    private fun continues(line: String): Boolean =
        !TidyGrammar.isBlank(line) && (TidyGrammar.isQuote(line) || !TidyGrammar.interrupts(line))

    private fun kindOf(line: String): RegionKind {
        // Preview's maths rule reads on past a quote's end, across blank lines.
        if (line.contains("$$")) return RegionKind.UNSURE
        // A list item or HTML tag under a quote may continue its paragraph or start a block.
        if (!TidyGrammar.isQuote(line) && mayStartBlock(line)) return RegionKind.UNSURE
        return if (isComplex(line)) RegionKind.COMPLEX else RegionKind.SIMPLE
    }

    private fun mayStartBlock(line: String): Boolean =
        TidyGrammar.listMarker(line) != null || line.getOrNull(TidyGrammar.firstText(line)) == '<' ||
            TidyGrammar.isDefinitionMarker(line) || TidyGrammar.isFootnoteOrAbbreviation(line)

    private fun isComplex(line: String): Boolean {
        if (line.contains('\t') || line.contains('|') || line.contains('<')) return true
        if (TidyGrammar.mayRunAcrossBlanks(line)) return true
        var i = TidyGrammar.firstText(line)
        while (i < line.length && line[i] == '>') {
            i++
            if (line.getOrNull(i) == ' ') i++
            while (i < line.length && line[i] == ' ' && line.getOrNull(i + 1) == '>') i++
        }
        // Four spaces after the markers is indented code inside the quote.
        return line.startsWith("    ", i)
    }
}
