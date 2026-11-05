package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Bulleted, numbered and task lists (docs/ux-spec.md, "Formatting strip").
 *
 * - Every selected line already that kind: the markers come off. One
 *   top-level item leaves its list as Outdent does ([ListContinuation.exitList]),
 *   so the rest of the list stays a list.
 * - Every selected line an item of the other kind (bullet for Numbered,
 *   numbered for Bullet): each whole list they are in switches kind, at
 *   their level, as a word processor's list-style switch does. Nested
 *   lines move with their item when the marker's width changes.
 * - Otherwise every line becomes that kind, keeping its quote and
 *   indentation, so a mixed selection turns uniform first and only then
 *   toggles off.
 */
internal object ListToggle {
    fun apply(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        kind: ListKind,
        fences: FenceStates = FenceStates.NONE,
    ): EditResult {
        val lines = TextLines.proseLines(text, selection, fences)
        if (lines.isEmpty()) return EditResult.unchanged(selection)
        val prefixes = lines.map { LinePrefix.parse(it.content) }
        val builder = EditBuilder(text)
        val single = lines.singleOrNull()
        if (single != null && prefixes[0].isKind(kind) && prefixes[0].indent.isEmpty()) {
            return ListContinuation.exitList(text, single, prefixes[0], selection)
        }
        if (prefixes.all { it.isKind(kind) }) {
            lines.forEachIndexed { i, line -> remove(builder, line.start, prefixes[i]) }
        } else if (kind != ListKind.Task && prefixes.all { it.isOtherKind(kind) }) {
            switchLists(text, builder, style, kind, lines, prefixes, fences)
        } else {
            val adder = Adder(builder, style, Numbering(text, lines.first(), prefixes.first(), style))
            lines.forEachIndexed { i, line -> if (!prefixes[i].isKind(kind)) adder.add(line.start, prefixes[i], kind) }
            separate(text, builder, lines.first(), lines.last(), prefixes.first())
        }
        return builder.buildMapping(selection)
    }

    private fun LinePrefix.isKind(kind: ListKind): Boolean =
        when (kind) {
            ListKind.Bullet -> marker is ListMarker.Bullet && !isTask
            ListKind.Numbered -> marker is ListMarker.Ordered
            ListKind.Task -> isTask
        }

    /**
     * Plain lines made into items between paragraph lines: a blank line
     * above and below keeps the paragraphs whole and the next line from
     * being a lazy continuation of the new item (EB-LIST-37).
     */
    private fun separate(
        text: CharSequence,
        builder: EditBuilder,
        first: LineSpan,
        last: LineSpan,
        firstPrefix: LinePrefix,
    ) {
        if (firstPrefix.isListItem || firstPrefix.quoteEnd > 0 || firstPrefix.indent.isNotEmpty()) return
        val above = TextLines.previous(text, first)
        // At the line above's end, so it never meets the marker written at this line's start.
        if (above != null && isParagraphLine(above)) builder.insert(above.end, "\n")
        val below = TextLines.next(text, last)
        if (below != null && isParagraphLine(below)) builder.insert(below.start, "\n")
    }

    private fun isParagraphLine(line: LineSpan): Boolean {
        val p = LinePrefix.parse(line.content)
        val starter = line.content.trimStart().firstOrNull()
        return !line.isBlank && !p.isListItem && p.quoteEnd == 0 && p.indent.isEmpty() && starter != '#' &&
            starter != '|' &&
            !line.content.startsWith("```") && !line.content.startsWith("~~~")
    }

    /** An item a [kind] key switches whole lists from: numbered for Bullet, any bullet (tasks too) for Numbered. */
    private fun LinePrefix.isOtherKind(kind: ListKind): Boolean =
        when (kind) {
            ListKind.Bullet -> marker is ListMarker.Ordered
            ListKind.Numbered -> marker is ListMarker.Bullet
            ListKind.Task -> false
        }

    /**
     * Rewrites the marker of every item in the lists [lines] are in, at their
     * level, as [kind]; task boxes stay. Numbers count from 1 in the
     * document's delimiter. Where the marker's width changes, the lines
     * nested in the item move by the same amount, so they stay nested. A
     * line nested under several switched items (a selection over a parent
     * and its child) moves by their sum, in one edit.
     */
    @Suppress("LongParameterList") // One pass over parsed lines; splitting it would only pass them around.
    private fun switchLists(
        text: CharSequence,
        builder: EditBuilder,
        style: DocumentStyle,
        kind: ListKind,
        lines: List<LineSpan>,
        prefixes: List<LinePrefix>,
        fences: FenceStates,
    ) {
        val done = HashSet<Int>()
        val shifts = LinkedHashMap<Int, Pair<LineSpan, Int>>()
        lines.forEachIndexed { i, line ->
            if (line.start in done) return@forEachIndexed
            val block = ListBlock.around(text, line, prefixes[i], fences)
            var number = 1L
            for (item in block.items) {
                done += item.span.start
                val p = item.prefix
                val marker =
                    if (kind ==
                        ListKind.Numbered
                    ) {
                        "${number++}${style.orderedDelimiter}"
                    } else {
                        "${style.bullet}"
                    }
                val old = p.markerEnd - p.indentEnd
                builder.replace(item.span.start + p.indentEnd, item.span.start + p.markerEnd, marker)
                val shift = marker.length - old
                if (shift != 0) {
                    for (child in item.children) {
                        val total = (shifts[child.start]?.second ?: 0) + shift
                        shifts[child.start] = child to total
                    }
                }
            }
        }
        for ((child, shift) in shifts.values) if (shift != 0) shiftIndent(builder, child, shift)
    }

    /** Moves a nested line by [shift] columns: spaces added after its quote, or up to that many removed. */
    private fun shiftIndent(
        builder: EditBuilder,
        line: LineSpan,
        shift: Int,
    ) {
        val p = LinePrefix.parse(line.content)
        val at = line.start + p.quoteEnd
        if (shift > 0) {
            builder.insert(at, " ".repeat(shift))
        } else {
            val spaces = p.indent.takeWhile { it == ' ' }.length
            builder.delete(at, at + minOf(spaces, -shift))
        }
    }

    /** Takes the whole marker off, task box included, so pressing a list key twice is a no-op. */
    private fun remove(
        builder: EditBuilder,
        at: Int,
        p: LinePrefix,
    ) = builder.delete(at + p.indentEnd, at + p.contentStart)

    /** Puts a list marker of a kind on lines that do not have one, keeping their quote and indentation. */
    private class Adder(
        private val builder: EditBuilder,
        private val style: DocumentStyle,
        private val numbering: Numbering,
    ) {
        fun add(
            at: Int,
            p: LinePrefix,
            kind: ListKind,
        ) {
            val markerStart = at + p.indentEnd
            when (kind) {
                ListKind.Bullet -> {
                    val bullet = (p.marker as? ListMarker.Bullet)?.char ?: style.bullet
                    // A task becoming a plain bullet loses its box.
                    builder.replace(markerStart, at + p.contentStart, "$bullet${p.markerSpacing}")
                }

                ListKind.Numbered -> {
                    val spacing = if (p.isListItem) "" else " "
                    val number = numbering.next(p.indent)
                    builder.replace(markerStart, at + p.markerEnd, "$number${numbering.delimiter}$spacing")
                }

                ListKind.Task if p.isListItem -> {
                    val spacing = if (p.bodyStart == p.markerEnd) " " else ""
                    builder.insert(at + p.bodyStart, "$spacing[ ] ")
                }

                ListKind.Task -> {
                    builder.insert(markerStart, "${style.bullet} [ ] ")
                }
            }
        }
    }

    /**
     * Numbers for lines becoming a numbered list. A list right above at the
     * same indent is continued, with its delimiter; otherwise counting
     * starts at 1 for each indent level.
     */
    private class Numbering(
        text: CharSequence,
        first: LineSpan,
        firstPrefix: LinePrefix,
        style: DocumentStyle,
    ) {
        private val counters = HashMap<String, Long>()
        val delimiter: Char

        init {
            // Blank lines between items keep one (loose) list, so the count goes on across them.
            var line = TextLines.previous(text, first)
            while (line != null && line.isBlank) line = TextLines.previous(text, line)
            val above = line?.let { LinePrefix.parse(it.content) }
            val marker = above?.marker
            if (marker is ListMarker.Ordered && above.indent == firstPrefix.indent &&
                above.quote == firstPrefix.quote
            ) {
                counters[above.indent] = marker.number + 1
                delimiter = marker.delimiter
            } else {
                delimiter = style.orderedDelimiter
            }
        }

        fun next(indent: String): Long {
            val n = counters[indent] ?: 1L
            counters[indent] = n + 1
            return n
        }
    }
}
