package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Bulleted, numbered and task lists. When every selected line is already
 * that kind the markers come off; otherwise every line becomes that kind,
 * keeping its quote and indentation, so a mixed selection turns uniform
 * first and only then toggles off.
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
        if (prefixes.all { it.isKind(kind) }) {
            lines.forEachIndexed { i, line -> remove(builder, line.start, prefixes[i]) }
        } else {
            val adder = Adder(builder, style, Numbering(text, lines.first(), prefixes.first(), style))
            lines.forEachIndexed { i, line -> if (!prefixes[i].isKind(kind)) adder.add(line.start, prefixes[i], kind) }
        }
        return builder.buildMapping(selection)
    }

    private fun LinePrefix.isKind(kind: ListKind): Boolean =
        when (kind) {
            ListKind.Bullet -> marker is ListMarker.Bullet && !isTask
            ListKind.Numbered -> marker is ListMarker.Ordered
            ListKind.Task -> isTask
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
            val above = TextLines.previous(text, first)?.let { LinePrefix.parse(it.content) }
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
