package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.LinearSyntax

/** Recognises the lines of a GFM table: a run of `|` lines that holds a delimiter row (`| --- | :-: |`). */
internal object TableLines {
    /** How far a table is searched for its delimiter row, each way. */
    private const val MAX_ROWS = 1_000

    fun isTableLine(
        text: CharSequence,
        line: LineSpan,
    ): Boolean {
        if ('|' !in line.content) return false
        if (isDelimiter(line.content)) return true
        for (forward in listOf(false, true)) {
            var current = if (forward) TextLines.next(text, line) else TextLines.previous(text, line)
            var walked = 0
            while (current != null && '|' in current.content && walked < MAX_ROWS) {
                if (isDelimiter(current.content)) return true
                current = if (forward) TextLines.next(text, current) else TextLines.previous(text, current)
                walked++
            }
        }
        return false
    }

    private fun isDelimiter(content: String): Boolean =
        '-' in content && LinearSyntax.isDelimiterRow(content, LinearSyntax::isRegexSpace)
}
