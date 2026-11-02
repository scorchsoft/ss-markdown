package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Keeps a numbered list counting after an item is inserted. Only the
 * numbers that are wrong change, and the walk stops at the first one that
 * is already right, so a long list costs one line, not all of them.
 */
internal object Renumbering {
    /** Upper bound on lines walked; a list longer than this is left as the author wrote it. */
    private const val MAX_LINES = 10_000

    /**
     * True for a list numbered `1. 1. 1.` on purpose, which Markdown
     * renders as 1, 2, 3. A new item copies the number rather than
     * "fixing" the author's choice.
     */
    fun isLazy(
        text: CharSequence,
        line: LineSpan,
        p: LinePrefix,
        marker: ListMarker.Ordered,
    ): Boolean {
        val above = TextLines.previous(text, line)?.let { LinePrefix.parse(it.content) }
        val previous = above?.marker as? ListMarker.Ordered
        return previous != null && above.quote == p.quote && above.indent == p.indent &&
            previous.number == marker.number
    }

    /** Renumbers the items after [line], the ordered item [p], at its level, starting from [expected]. */
    fun after(
        text: CharSequence,
        builder: EditBuilder,
        line: LineSpan,
        p: LinePrefix,
        expected: Long,
    ) {
        val delimiter = requireNotNull(p.marker as? ListMarker.Ordered) { "not an ordered item" }.delimiter
        var number = expected
        var current = TextLines.next(text, line)
        var walked = 0
        var done = false
        while (!done && current != null && walked < MAX_LINES) {
            val item = LinePrefix.parse(current.content)
            val nested = item.quote == p.quote && item.indentWidth > p.indentWidth
            val sameLevel = item.quote == p.quote && item.indentWidth == p.indentWidth
            val sibling = (item.marker as? ListMarker.Ordered)?.takeIf { sameLevel && it.delimiter == delimiter }
            when {
                current.isBlank || nested -> {
                    Unit
                }

                sibling == null || sibling.number == number -> {
                    done = true
                }

                else -> {
                    val digitsStart = current.start + item.indentEnd
                    builder.replace(digitsStart, current.start + item.markerEnd - 1, number.toString())
                    number++
                }
            }
            current = TextLines.next(text, current)
            walked++
        }
    }
}
