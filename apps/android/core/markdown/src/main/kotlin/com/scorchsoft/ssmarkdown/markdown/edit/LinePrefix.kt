// A recogniser bails out the moment the line stops matching.
@file:Suppress("ReturnCount")

package com.scorchsoft.ssmarkdown.markdown.edit

/** A list item's marker: a bullet char, or a number with its `.` or `)`. */
internal sealed interface ListMarker {
    data class Bullet(
        val char: Char,
    ) : ListMarker

    data class Ordered(
        val number: Long,
        val delimiter: Char,
    ) : ListMarker
}

/**
 * The container syntax at the start of a line, as columns into it:
 *
 * ```
 * > ␣␣- [x] Ship it
 * ╰quote╯
 *   ╰ind╯╰m╯╰task╯╰content
 * ```
 *
 * Only what the edit commands need is recognised: blockquote markers,
 * indentation, one list marker and a GFM task box. Lists inside lists on
 * the same line (`- - x`) are rare enough to treat as content.
 */
@Suppress("LongParameterList") // The parsed columns of one line: a value type, built in one place by parse().
internal class LinePrefix private constructor(
    val line: String,
    /** End of the blockquote markers, e.g. 2 for `> x`; 0 outside a quote. */
    val quoteEnd: Int,
    /** End of the indentation that follows the quote. */
    val indentEnd: Int,
    val marker: ListMarker?,
    /** End of the marker itself (`-`, `12.`); equal to [indentEnd] when there is none. */
    val markerEnd: Int,
    /** Where an item's own text starts, after the marker's spaces and before any task box. */
    val bodyStart: Int,
    /** The column of the char inside `[ ]`, or -1 when the item is not a task. */
    val taskColumn: Int,
    /** Where the user's text starts. */
    val contentStart: Int,
) {
    val quote: String get() = line.substring(0, quoteEnd)
    val indent: String get() = line.substring(quoteEnd, indentEnd)
    val isListItem: Boolean get() = marker != null
    val isTask: Boolean get() = taskColumn >= 0
    val isChecked: Boolean get() = isTask && line[taskColumn] != ' '
    val content: String get() = line.substring(contentStart)

    /** The spaces between the marker and the body, at least one so a continuation is a valid item. */
    val markerSpacing: String get() = line.substring(markerEnd, bodyStart).ifEmpty { " " }

    /** Indentation width with tabs at 4 columns, enough to compare nesting levels. */
    val indentWidth: Int get() = indent.sumOf { if (it == '\t') TAB_WIDTH else 1 }

    companion object {
        const val TAB_WIDTH = 4
        private const val MAX_QUOTE_INDENT = 3
        private const val MAX_ORDINAL_DIGITS = 9

        fun parse(line: String): LinePrefix {
            val quoteEnd = quoteEnd(line)
            var i = quoteEnd
            while (i < line.length && isSpace(line[i])) i++
            val indentEnd = i
            val markerEnd = markerEnd(line, indentEnd)
            if (markerEnd < 0) return LinePrefix(line, quoteEnd, indentEnd, null, indentEnd, indentEnd, -1, indentEnd)
            val marker = markerAt(line, indentEnd, markerEnd)
            var bodyStart = markerEnd
            while (bodyStart < line.length && isSpace(line[bodyStart])) bodyStart++
            val taskColumn = if (isTaskBox(line, bodyStart)) bodyStart + 1 else -1
            var contentStart = bodyStart
            if (taskColumn >= 0) {
                contentStart = bodyStart + TASK_BOX.length
                while (contentStart < line.length && isSpace(line[contentStart])) contentStart++
            }
            return LinePrefix(line, quoteEnd, indentEnd, marker, markerEnd, bodyStart, taskColumn, contentStart)
        }

        fun isSpace(c: Char): Boolean = c == ' ' || c == '\t'

        private const val TASK_BOX = "[ ]"

        private fun quoteEnd(line: String): Int {
            var end = 0
            var i = 0
            while (true) {
                var spaces = 0
                while (i < line.length && line[i] == ' ' && spaces < MAX_QUOTE_INDENT) {
                    i++
                    spaces++
                }
                if (i >= line.length || line[i] != '>') return end
                i++
                if (i < line.length && line[i] == ' ') i++
                end = i
            }
        }

        /** End of a list marker starting at [from], or -1. A marker is followed by a space or the line end. */
        private fun markerEnd(
            line: String,
            from: Int,
        ): Int {
            if (from >= line.length) return -1
            val end =
                if (line[from] in "-+*") {
                    from + 1
                } else {
                    var i = from
                    while (i < line.length && line[i].isAsciiDigit() && i - from < MAX_ORDINAL_DIGITS) i++
                    if (i == from || i >= line.length || line[i] !in ".)") return -1
                    i + 1
                }
            return if (end == line.length || isSpace(line[end])) end else -1
        }

        private fun markerAt(
            line: String,
            from: Int,
            end: Int,
        ): ListMarker =
            if (end - from == 1 && line[from] in "-+*") {
                ListMarker.Bullet(line[from])
            } else {
                ListMarker.Ordered(line.substring(from, end - 1).toLong(), line[end - 1])
            }

        private fun isTaskBox(
            line: String,
            at: Int,
        ): Boolean {
            if (at + TASK_BOX.length > line.length) return false
            if (line[at] != '[' || line[at + 2] != ']' || line[at + 1] !in " xX") return false
            val after = at + TASK_BOX.length
            return after == line.length || isSpace(line[after])
        }

        private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
    }
}
