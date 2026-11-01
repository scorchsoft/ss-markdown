package com.scorchsoft.ssmarkdown.markdown.edit

/** One line of the text: `text[start, end)`, without its `'\n'`. */
internal class LineSpan(
    val start: Int,
    val end: Int,
    val content: String,
) {
    val isBlank: Boolean get() = content.isBlank()
}

/**
 * Line arithmetic over a plain [CharSequence] in memory form (`'\n'` is the
 * only break). Commands touch a handful of lines around the selection, so
 * a local scan is cheaper than asking a line index and keeps them pure.
 */
internal object TextLines {
    fun lineStart(
        text: CharSequence,
        offset: Int,
    ): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    fun lineEnd(
        text: CharSequence,
        offset: Int,
    ): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    /** The line holding [offset]. */
    fun lineAt(
        text: CharSequence,
        offset: Int,
    ): LineSpan {
        val start = lineStart(text, offset)
        val end = lineEnd(text, offset)
        return LineSpan(start, end, text.subSequence(start, end).toString())
    }

    /** The line before [line], or null on the first line. */
    fun previous(
        text: CharSequence,
        line: LineSpan,
    ): LineSpan? = if (line.start == 0) null else lineAt(text, line.start - 1)

    /** The line after [line], or null on the last line. */
    fun next(
        text: CharSequence,
        line: LineSpan,
    ): LineSpan? = if (line.end >= text.length) null else lineAt(text, line.end + 1)

    /**
     * The lines a line command acts on. A selection that ends at the very
     * start of a line does not take that line too: dragging to the start
     * of the next line is how people select whole lines.
     */
    fun linesIn(
        text: CharSequence,
        selection: TextSelection,
    ): List<LineSpan> {
        val last =
            if (!selection.isCaret &&
                selection.end == lineStart(text, selection.end)
            ) {
                selection.end - 1
            } else {
                selection.end
            }
        val lines = ArrayList<LineSpan>()
        var line: LineSpan? = lineAt(text, selection.start)
        while (line != null) {
            lines += line
            line = if (line.end >= last) null else next(text, line)
        }
        return lines
    }

    /** The lines a command changes: the non-blank ones, or the single blank line the caret sits on. */
    fun targetLines(
        text: CharSequence,
        selection: TextSelection,
    ): List<LineSpan> {
        val lines = linesIn(text, selection)
        return lines.filterNot { it.isBlank }.ifEmpty { lines.take(1) }
    }

    /**
     * [targetLines] without the lines of fenced code: a heading or list
     * toggle never rewrites code. Empty when every target line is code.
     */
    fun proseLines(
        text: CharSequence,
        selection: TextSelection,
        fences: FenceStates,
    ): List<LineSpan> = targetLines(text, selection).filterNot { fences.roleAt(it.start).isCode }
}
