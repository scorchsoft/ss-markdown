package com.scorchsoft.ssmarkdown.markdown.edit

/** Links and tables: commands that insert new syntax rather than toggle it. */
internal object InsertEdit {
    private val urlLike = Regex("""^(https?://|mailto:|www\.)\S+$""")

    /**
     * `[text](|)` around a selection, with the caret where the address goes.
     * A selected address becomes the target instead: `[|](https://…)`.
     * With no selection, `[|]()`.
     */
    fun link(
        text: CharSequence,
        selection: TextSelection,
    ): EditResult {
        val builder = EditBuilder(text)
        val selected = text.subSequence(selection.start, selection.end).toString()
        val caret =
            when {
                selection.isCaret -> {
                    builder.insert(selection.start, "[]()")
                    selection.start + 1
                }

                urlLike.matches(selected) -> {
                    builder.insert(selection.end, ")")
                    builder.insert(selection.start, "[](")
                    selection.start + 1
                }

                else -> {
                    builder.insert(selection.end, "]()")
                    builder.insert(selection.start, "[")
                    selection.end + "[](".length
                }
            }
        return builder.build(TextSelection.caret(caret))
    }

    /**
     * A table skeleton of [columns] × [rows] body rows, in the document's
     * padded or compact style, on its own lines with a blank line either
     * side so it can never merge into a paragraph. The first header cell
     * is selected, ready to type over.
     */
    fun table(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        columns: Int,
        rows: Int,
    ): EditResult {
        require(columns >= 1 && rows >= 0) { "a table needs a column; got $columns × $rows" }
        val line = TextLines.lineAt(text, selection.end)
        val headers = (1..columns).map { "Column $it" }
        val table = if (style.paddedTables) padded(headers, rows) else compact(headers, rows)
        val above = TextLines.previous(text, line)
        val below = TextLines.next(text, line)
        // On a blank line the table takes its place; otherwise it goes after the line.
        val at = if (line.isBlank) line.start else line.end
        val before =
            when {
                !line.isBlank -> "\n\n"
                above != null && !above.isBlank -> "\n"
                else -> ""
            }
        val after = if (below != null && !below.isBlank) "\n" else ""
        val builder = EditBuilder(text)
        builder.replace(at, line.end, before + table + after)
        val cellStart = at + before.length + table.indexOf(headers.first())
        return builder.build(TextSelection(cellStart, cellStart + headers.first().length))
    }

    private fun padded(
        headers: List<String>,
        rows: Int,
    ): String {
        val widths = headers.map { it.length }
        val header = headers.joinToString(" | ", "| ", " |")
        val delimiter = widths.joinToString(" | ", "| ", " |") { "-".repeat(it) }
        val body = widths.joinToString(" | ", "| ", " |") { " ".repeat(it) }
        return (listOf(header, delimiter) + List(rows) { body }).joinToString("\n")
    }

    private fun compact(
        headers: List<String>,
        rows: Int,
    ): String {
        val header = headers.joinToString("|", "|", "|")
        val delimiter = headers.joinToString("|", "|", "|") { "---" }
        // A cell needs something in it to read as a cell when typed into; one space is the least.
        val body = headers.joinToString("|", "|", "|") { " " }
        return (listOf(header, delimiter) + List(rows) { body }).joinToString("\n")
    }
}
