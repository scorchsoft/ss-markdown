package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Works out the numbers of a numbered list after its shape changes:
 * lines indented or outdented, or an item deleted. The rules are the ones
 * a person would apply by hand:
 *
 * - siblings at one level count up from the level's first item;
 * - an item that starts a level keeps its number (a list may start at 5),
 *   unless it only became first because of this change (it moved, or the
 *   items before it moved away), in which case it starts at 1;
 * - a list numbered `1. 1. 1.` on purpose stays that way (see
 *   [Renumbering.isLazy]);
 * - fenced code is not part of the list's structure, so `1.` inside a
 *   fence is never renumbered.
 *
 * Only lines from the first changed line down can change: numbering
 * depends only on the lines above. The walk is bounded, like every
 * command, so a vast list costs a bounded number of lines.
 */
internal object ListNumbering {
    private const val MAX_LOOKBACK = 500
    private const val MAX_LOOKAHEAD = 10_000

    /**
     * One line of the list region. [widthAfter] is its indent width once
     * the change applies; a line whose width changes has [moved].
     */
    class Row(
        val span: LineSpan,
        val prefix: LinePrefix,
        val code: Boolean,
        val widthAfter: Int,
    ) {
        val widthBefore: Int get() = prefix.indentWidth
        val moved: Boolean get() = widthAfter != widthBefore
    }

    /**
     * The list region around [first]..[last]: up to the paragraph or quote
     * change that bounds the list above, and down to the one below.
     * [widthAfter] gives a line's new indent width (its old one if unchanged).
     */
    fun region(
        text: CharSequence,
        first: LineSpan,
        last: LineSpan,
        fences: FenceStates,
        widthAfter: (LineSpan, LinePrefix) -> Int = { _, p -> p.indentWidth },
    ): List<Row> {
        val quote = LinePrefix.parse(first.content).quote
        val above = ArrayList<LineSpan>()
        var line = TextLines.previous(text, first)
        while (line != null && above.size < MAX_LOOKBACK && !boundsAbove(line, quote)) {
            above += line
            line = TextLines.previous(text, line)
        }
        val spans = above.asReversed().toMutableList()
        line = first
        while (line != null && line.start <= last.start) {
            spans += line
            line = TextLines.next(text, line)
        }
        var walked = 0
        var previousBlank = last.isBlank
        while (line != null && walked < MAX_LOOKAHEAD && !boundsBelow(line, quote, previousBlank, fences)) {
            spans += line
            previousBlank = line.isBlank
            line = TextLines.next(text, line)
            walked++
        }
        return spans.map { span ->
            val p = LinePrefix.parse(span.content)
            Row(span, p, fences.roleAt(span.start).isCode, widthAfter(span, p))
        }
    }

    /**
     * The new number of every ordered item at or below [from] whose number
     * is wrong after the change, keyed by its line start. With
     * [restartAtChange], an item at or below [from] that now starts a level
     * starts it at 1: it is first only because the items before it went.
     */
    fun changes(
        rows: List<Row>,
        from: Int,
        restartAtChange: Boolean = false,
    ): Map<Int, Long> {
        val firsts = Walker(rows, from, startedLevel = null).run { it.widthBefore }.startedLevel
        if (restartAtChange) rows.forEachIndexed { i, row -> if (row.span.start >= from) firsts[i] = false }
        val after = Walker(rows, from, firsts).run { it.widthAfter }.numbers
        val out = LinkedHashMap<Int, Long>()
        rows.forEachIndexed { i, row ->
            val old = (row.prefix.marker as? ListMarker.Ordered)?.number
            val wrong = old != null && after[i] >= 0 && after[i] != old
            if (row.span.start >= from && wrong) out[row.span.start] = after[i]
        }
        return out
    }

    /** Replaces the digits of the item [p] on [line] with [number]. */
    fun writeNumber(
        builder: EditBuilder,
        line: LineSpan,
        p: LinePrefix,
        number: Long,
    ) = builder.replace(line.start + p.indentEnd, line.start + p.markerEnd - 1, number.toString())

    /** Walking up, any text at the margin (a paragraph or a fence) or a quote change is outside the list. */
    private fun boundsAbove(
        line: LineSpan,
        quote: String,
    ): Boolean {
        val p = LinePrefix.parse(line.content)
        return p.quote != quote || (!line.isBlank && !p.isListItem && p.indentWidth == 0)
    }

    /**
     * Walking down, the list ends at a quote change, a fence at the margin
     * (a fence interrupts a list), or margin text after a blank line; margin
     * text right after an item is a lazy continuation of it.
     */
    private fun boundsBelow(
        line: LineSpan,
        quote: String,
        previousBlank: Boolean,
        fences: FenceStates,
    ): Boolean {
        val p = LinePrefix.parse(line.content)
        val marginText = !line.isBlank && !p.isListItem && p.indentWidth == 0
        val role = if (marginText) fences.roleAt(line.start) else FenceRole.OUTSIDE
        val ends = role == FenceRole.OPENING_FENCE || (previousBlank && !role.isCode)
        return p.quote != quote || (marginText && ends)
    }

    private class Walk(
        val numbers: LongArray,
        val startedLevel: BooleanArray,
    )

    private class Level(
        val width: Int,
        val delimiter: Char?,
        val firstNumber: Long,
        val firstMoved: Boolean,
    ) {
        var next: Long = firstNumber + 1
        var count: Int = 1
        var lazy: Boolean = false
    }

    /**
     * Numbers every ordered item of one shape of the list. [startedLevel]
     * is the previous shape's "first of its level" flags; null means this
     * is the previous shape itself, so every first item keeps its number.
     */
    private class Walker(
        private val rows: List<Row>,
        private val from: Int,
        private val startedLevel: BooleanArray?,
    ) {
        private val numbers = LongArray(rows.size) { -1 }
        private val started = BooleanArray(rows.size)
        private val stack = ArrayList<Level>()

        fun run(widthOf: (Row) -> Int): Walk {
            var sawBlank = false
            rows.forEachIndexed { i, row ->
                val width = widthOf(row)
                when {
                    row.code -> {
                        sawBlank = false
                    }

                    row.span.isBlank -> {
                        sawBlank = true
                    }

                    !row.prefix.isListItem -> {
                        // After a blank line, text at a level ends the lists nested at or beyond it.
                        if (sawBlank) popWhile { it.width >= width }
                        sawBlank = false
                    }

                    else -> {
                        sawBlank = false
                        popWhile { it.width > width }
                        val top = stack.lastOrNull()?.takeIf { it.width == width }
                        numbers[i] =
                            if (top != null && continues(top, row)) next(top, row) else open(i, row, width, top)
                        started[i] = stack.last() !== top
                    }
                }
            }
            return Walk(numbers, started)
        }

        private fun popWhile(predicate: (Level) -> Boolean) {
            while (stack.lastOrNull()?.let(predicate) == true) stack.removeAt(stack.lastIndex)
        }

        private fun continues(
            top: Level,
            row: Row,
        ): Boolean = (row.prefix.marker as? ListMarker.Ordered)?.delimiter.let { it != null && it == top.delimiter }

        /** The next sibling of [top]'s list. */
        private fun next(
            top: Level,
            row: Row,
        ): Long {
            val number = (row.prefix.marker as ListMarker.Ordered).number
            // Only a pair above the change shows intent: below it, "1. 1." may be the gap this change made.
            val settled = row.span.start < from && !row.moved && !top.firstMoved
            if (top.count == 1 && number == top.firstNumber && settled) top.lazy = true
            val result = if (top.lazy) top.firstNumber else top.next
            top.next = result + 1
            top.count++
            return result
        }

        /** Starts a new level with [row], replacing a [top] of another kind; -1 for a bullet. */
        private fun open(
            i: Int,
            row: Row,
            width: Int,
            top: Level?,
        ): Long {
            if (top != null) stack.removeAt(stack.lastIndex)
            val ordered = row.prefix.marker as? ListMarker.Ordered
            val keep = startedLevel == null || (startedLevel[i] && !row.moved)
            val start =
                when {
                    ordered == null -> -1L
                    keep -> ordered.number
                    else -> 1L
                }
            stack += Level(width, ordered?.delimiter, start, row.moved)
            return start
        }
    }
}
