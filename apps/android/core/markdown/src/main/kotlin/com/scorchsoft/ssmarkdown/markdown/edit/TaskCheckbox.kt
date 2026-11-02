package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Ticks or unticks the task on a line by changing exactly one char, the
 * one inside `[ ]` (docs/research-findings.md R12). Preview's checkbox
 * taps land here, so nothing else in the file may move.
 */
internal object TaskCheckbox {
    fun apply(
        text: CharSequence,
        selection: TextSelection,
        lineOffset: Int,
    ): EditResult {
        require(lineOffset in 0..text.length) { "offset $lineOffset outside text of ${text.length}" }
        val line = TextLines.lineAt(text, lineOffset)
        val prefix = LinePrefix.parse(line.content)
        if (!prefix.isTask) return EditResult.unchanged(selection)
        val at = line.start + prefix.taskColumn
        val builder = EditBuilder(text)
        builder.replace(at, at + 1, if (prefix.isChecked) " " else "x")
        return builder.build(selection)
    }
}
