package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Indent and outdent, list-aware. A list item nests under the item above
 * by that item's marker width (`- ` is 2, `10. ` is 4), which is what
 * CommonMark needs to see a sublist; other lines move by the document's
 * [DocumentStyle.indentUnit]. A tab-indented document always gets tabs.
 */
internal object IndentEdit {
    /** How far up to look for a sibling or parent item; lists are rarely longer, and the cost stays bounded. */
    private const val MAX_LOOKBACK_LINES = 200

    fun indent(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
    ): EditResult {
        val builder = EditBuilder(text)
        for (line in TextLines.targetLines(text, selection)) {
            val p = LinePrefix.parse(line.content)
            val unit =
                when {
                    style.indentUnit == "\t" -> "\t"
                    p.isListItem -> " ".repeat(nestingWidth(text, line, p))
                    else -> style.indentUnit
                }
            builder.insert(line.start + p.quoteEnd, unit)
        }
        return builder.buildMapping(selection)
    }

    fun outdent(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
    ): EditResult {
        val builder = EditBuilder(text)
        for (line in TextLines.targetLines(text, selection)) {
            val p = LinePrefix.parse(line.content)
            val remove = outdentWidth(text, line, p, style)
            if (remove > 0) builder.delete(line.start + p.quoteEnd, line.start + p.quoteEnd + remove)
        }
        return builder.buildMapping(selection)
    }

    /** Chars of leading indentation one outdent removes from [line]. */
    internal fun outdentWidth(
        text: CharSequence,
        line: LineSpan,
        p: LinePrefix,
        style: DocumentStyle,
    ): Int {
        val indent = p.indent
        val spaces = indent.takeWhile { it == ' ' }.length
        val oneUnitIn = maxOf(0, spaces - style.indentUnit.length)
        return when {
            indent.isEmpty() -> 0
            indent[0] == '\t' -> 1
            p.isListItem -> spaces - (parentIndent(text, line, p) ?: oneUnitIn).coerceIn(0, spaces - 1)
            else -> spaces - oneUnitIn
        }
    }

    /** The marker width of the item above at the same level, else this item's own. */
    private fun nestingWidth(
        text: CharSequence,
        line: LineSpan,
        p: LinePrefix,
    ): Int {
        val sibling = itemsAbove(text, line, p).firstOrNull { it.indentWidth == p.indentWidth }
        val item = sibling ?: p
        // An empty item (`-` at the end of a line) still needs its marker plus one space.
        return maxOf(item.bodyStart, item.markerEnd + 1) - item.indentEnd
    }

    /** The indent of the nearest item above with a smaller one: the item this one is nested in. */
    private fun parentIndent(
        text: CharSequence,
        line: LineSpan,
        p: LinePrefix,
    ): Int? = itemsAbove(text, line, p).firstOrNull { it.indentWidth < p.indentWidth }?.indent?.length

    /** List items above [line] in the same quote, nearest first, stopping at a paragraph that ends the list. */
    private fun itemsAbove(
        text: CharSequence,
        line: LineSpan,
        p: LinePrefix,
    ): Sequence<LinePrefix> =
        sequence {
            var current = TextLines.previous(text, line)
            var seen = 0
            while (current != null && seen < MAX_LOOKBACK_LINES) {
                val above = LinePrefix.parse(current.content)
                val endsList = !above.isListItem && above.indent.isEmpty() && current.content.isNotBlank()
                if (above.quote != p.quote || endsList) break
                if (above.isListItem) yield(above)
                current = TextLines.previous(text, current)
                seen++
            }
        }
}
