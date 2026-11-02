package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Adds or removes one level of `> ` on the selected lines. Blank lines
 * between quoted lines get a bare `>` so the quote stays one block
 * without leaving trailing spaces behind.
 */
internal object BlockquoteToggle {
    fun apply(
        text: CharSequence,
        selection: TextSelection,
    ): EditResult {
        val lines = TextLines.linesIn(text, selection)
        val targets = TextLines.targetLines(text, selection)
        val builder = EditBuilder(text)
        if (targets.all { quoteMarker(it.content) != null }) {
            for (line in targets) {
                val marker = requireNotNull(quoteMarker(line.content))
                builder.delete(line.start + marker.first, line.start + marker.last + 1)
            }
        } else {
            for (line in lines) {
                val marker = if (line.content.isNotBlank() || lines.size == 1) "> " else ">"
                builder.insert(line.start, marker)
            }
        }
        return builder.buildMapping(selection)
    }

    /** The columns of the first `>` and its optional space, or null when the line is not quoted. */
    private fun quoteMarker(line: String): IntRange? {
        val end = LinePrefix.parse(line).quoteEnd
        if (end == 0) return null
        val gt = line.indexOf('>')
        val last = if (gt + 1 < line.length && line[gt + 1] == ' ') gt + 1 else gt
        return gt..last
    }
}
