package com.scorchsoft.ssmarkdown.markdown.edit

/** ATX headings: set a level on every selected line, or step the caret line's level. */
internal object HeadingEdit {
    const val MAX_LEVEL = 6

    /** The level [CycleHeading][EditCommand.CycleHeading] stops at before going back to a paragraph. */
    const val CYCLE_MAX_LEVEL = 3

    private const val MAX_INDENT = 3

    /** The ATX level of [line] (0 for none) and the end of its `### ` prefix; [line] starts after any quote. */
    fun parse(line: String): Pair<Int, Int> {
        var i = 0
        while (i < line.length && i < MAX_INDENT && line[i] == ' ') i++
        val hashes = line.drop(i).takeWhile { it == '#' }.length
        val after = i + hashes
        if (hashes !in 1..MAX_LEVEL || (after < line.length && !LinePrefix.isSpace(line[after]))) return 0 to 0
        var end = after
        while (end < line.length && LinePrefix.isSpace(line[end])) end++
        return hashes to end
    }

    fun set(
        text: CharSequence,
        selection: TextSelection,
        level: Int,
    ): EditResult {
        require(level in 0..MAX_LEVEL) { "heading level $level is not 0..$MAX_LEVEL" }
        val builder = EditBuilder(text)
        for (line in TextLines.targetLines(text, selection)) {
            // Inside a blockquote the heading goes after the `> `.
            val base = line.start + LinePrefix.parse(line.content).quoteEnd
            val (_, prefixEnd) = parse(text.subSequence(base, line.end).toString())
            val prefix = if (level == 0) "" else "#".repeat(level) + " "
            builder.replace(base, base + prefixEnd, prefix)
        }
        return builder.buildMapping(selection)
    }

    /** Paragraph → H1 → H2 → H3 → paragraph, from the first selected line's level. */
    fun cycle(
        text: CharSequence,
        selection: TextSelection,
    ): EditResult {
        val first = TextLines.targetLines(text, selection).first().content
        val (level, _) = parse(first.substring(LinePrefix.parse(first).quoteEnd))
        val next = if (level >= CYCLE_MAX_LEVEL) 0 else level + 1
        return set(text, selection, next)
    }
}
