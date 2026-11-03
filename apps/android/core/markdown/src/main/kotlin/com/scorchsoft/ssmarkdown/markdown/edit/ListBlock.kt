package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * The list an item belongs to, at the item's own level: its sibling items
 * (same quote, same indentation, same kind of marker: one bullet char, or
 * one ordered delimiter, which is where CommonMark starts a new list) and
 * the lines nested in each (deeper indentation, fenced code, and the blank
 * lines between). Bounded both ways, so a huge list costs a bounded walk.
 */
internal class ListBlock private constructor(
    /** The items at the level, top to bottom, each with the lines nested under it. */
    val items: List<Item>,
) {
    class Item(
        val span: LineSpan,
        val prefix: LinePrefix,
        /** Non-blank lines nested in this item, before the next sibling. */
        val children: List<LineSpan>,
    )

    companion object {
        private const val MAX_LINES = 2_000

        /** The list [line] (an item, [p]) is in, at its level. */
        fun around(
            text: CharSequence,
            line: LineSpan,
            p: LinePrefix,
            fences: FenceStates,
        ): ListBlock {
            val up = walk(text, line, p, fences, forward = false).asReversed()
            val down = walk(text, line, p, fences, forward = true)
            // Above the first sibling, deeper lines belong to something else.
            val firstItem = up.indexOfFirst { it.second != null }
            val lines = (if (firstItem < 0) emptyList() else up.drop(firstItem)) + listOf(line to p) + down
            val items = ArrayList<Item>()
            var current: Pair<LineSpan, LinePrefix>? = null
            var children = ArrayList<LineSpan>()
            for ((span, prefix) in lines) {
                if (prefix != null) {
                    current?.let { items += Item(it.first, it.second, children) }
                    current = span to prefix
                    children = ArrayList()
                } else if (!span.isBlank) {
                    children += span
                }
            }
            current?.let { items += Item(it.first, it.second, children) }
            return ListBlock(items)
        }

        /** Lines away from [line] that are still in its list: siblings carry their prefix, the rest null. */
        @Suppress("CyclomaticComplexMethod") // One pass sorting each line into blank, sibling, nested or the end.
        private fun walk(
            text: CharSequence,
            line: LineSpan,
            p: LinePrefix,
            fences: FenceStates,
            forward: Boolean,
        ): List<Pair<LineSpan, LinePrefix?>> {
            val out = ArrayList<Pair<LineSpan, LinePrefix?>>()
            var current = step(text, line, forward)
            var pendingBlanks = 0
            while (current != null && out.size < MAX_LINES) {
                val q = LinePrefix.parse(current.content)
                val sameQuote = q.quote.trimEnd() == p.quote.trimEnd()
                val blank = sameQuote && current.content.substring(q.quoteEnd).isBlank()
                val code = fences.roleAt(current.start).let { it == FenceRole.INSIDE || it == FenceRole.CLOSING_FENCE }
                when {
                    blank -> {
                        out += current to null
                        pendingBlanks++
                    }

                    sameQuote && q.isListItem && q.indentWidth == p.indentWidth && sameKind(q, p) -> {
                        out += current to q
                        pendingBlanks = 0
                    }

                    sameQuote && (q.indentWidth > p.indentWidth || code) -> {
                        out += current to null
                        pendingBlanks = 0
                    }

                    else -> {
                        break
                    }
                }
                current = step(text, current, forward)
            }
            // Blank lines at the far end are not the list's.
            repeat(pendingBlanks) { out.removeAt(out.size - 1) }
            return out
        }

        private fun step(
            text: CharSequence,
            line: LineSpan,
            forward: Boolean,
        ): LineSpan? = if (forward) TextLines.next(text, line) else TextLines.previous(text, line)

        private fun sameKind(
            a: LinePrefix,
            b: LinePrefix,
        ): Boolean {
            val x = a.marker
            val y = b.marker
            return when {
                x is ListMarker.Bullet && y is ListMarker.Bullet -> x.char == y.char
                x is ListMarker.Ordered && y is ListMarker.Ordered -> x.delimiter == y.delimiter
                else -> false
            }
        }
    }
}
