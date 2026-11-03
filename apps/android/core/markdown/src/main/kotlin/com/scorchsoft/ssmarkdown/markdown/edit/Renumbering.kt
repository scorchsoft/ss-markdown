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

    /**
     * The items after [line] (the ordered item [p], which has just stopped
     * being one) become a list of their own that continues the count: they
     * are renumbered from [start], the number [line] had. A list starting
     * anywhere but 1 cannot interrupt a paragraph (CommonMark), so when the
     * next item sits right under [line] a blank line goes between them.
     */
    fun splitRest(
        text: CharSequence,
        builder: EditBuilder,
        line: LineSpan,
        p: LinePrefix,
        start: Long,
    ) {
        val next = TextLines.next(text, line)
        val item = next?.let { LinePrefix.parse(it.content) }
        val delimiter = (p.marker as? ListMarker.Ordered)?.delimiter
        val marker = item?.marker as? ListMarker.Ordered
        val sameLevel = item?.quote == p.quote && item.indentWidth == p.indentWidth
        val sibling = next?.takeIf { marker?.delimiter == delimiter && sameLevel }
        if (sibling == null || item == null || start == 1L) {
            after(text, builder, line, p, start)
            return
        }
        // The first item takes the blank line and its number in one op, so the two never share an offset.
        val prefix = item.line.substring(0, item.indentEnd)
        builder.replace(sibling.start, sibling.start + item.markerEnd - 1, p.quote.trimEnd() + "\n" + prefix + start)
        after(text, builder, sibling, p, start + 1)
    }
}
