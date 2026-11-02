package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Enter. In a list it starts the next item (same bullet, the next number,
 * a fresh `[ ]`), and later items of a numbered list are renumbered. On
 * an empty item it ends the list instead: a nested item steps out one
 * level, a top-level one loses its marker. Quotes continue the same way,
 * and an indented line keeps its indentation.
 *
 * The keyboard's composing word must be committed before this runs
 * (docs/research-findings.md R6); the editor does that, not this.
 */
internal object ListContinuation {
    fun apply(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        fences: FenceStates = FenceStates.NONE,
    ): EditResult {
        val line = TextLines.lineAt(text, selection.start)
        val column = selection.start - line.start
        val p = LinePrefix.parse(line.content)
        if (fences.roleAt(line.start).isCode) {
            // `- ` in code is code: keep the quote and indentation, never continue a "list".
            val keep = if (column >= p.indentEnd) p.quote + p.indent else ""
            return newline(text, selection, keep)
        }
        return prose(text, selection, style, line, p)
    }

    private fun prose(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        line: LineSpan,
        p: LinePrefix,
    ): EditResult {
        val column = selection.start - line.start
        val endsOnLine = selection.end <= line.end
        val emptyItem = selection.isCaret && p.content.isBlank() && column >= p.contentStart
        return when {
            p.isListItem && emptyItem -> {
                endItem(text, selection, line, p, style)
            }

            p.isListItem && column >= p.contentStart -> {
                nextItem(text, selection, line, p, endsOnLine)
            }

            p.quoteEnd > 0 && column >= p.quoteEnd && emptyItem && p.indent.isEmpty() -> {
                replace(text, line.start, line.end, "")
            }

            column >= p.indentEnd -> {
                newline(text, selection, p.quote + p.indent)
            }

            else -> {
                newline(text, selection, "")
            }
        }
    }

    private fun nextItem(
        text: CharSequence,
        selection: TextSelection,
        line: LineSpan,
        p: LinePrefix,
        endsOnLine: Boolean,
    ): EditResult {
        val marker = p.marker
        val next =
            when (marker) {
                is ListMarker.Ordered -> {
                    val lazy = Renumbering.isLazy(text, line, p, marker)
                    "${if (lazy) marker.number else marker.number + 1}${marker.delimiter}"
                }

                is ListMarker.Bullet -> {
                    marker.char.toString()
                }

                null -> {
                    ""
                }
            }
        val task = if (p.isTask) "[ ] " else ""
        val continuation = p.quote + p.indent + next + p.markerSpacing + task
        val builder = EditBuilder(text)
        builder.replace(selection.start, selection.end, "\n" + continuation)
        if (marker is ListMarker.Ordered && endsOnLine && !Renumbering.isLazy(text, line, p, marker)) {
            Renumbering.after(text, builder, line, p, marker.number + 2)
        }
        return builder.build(TextSelection.caret(selection.start + 1 + continuation.length))
    }

    private fun endItem(
        text: CharSequence,
        selection: TextSelection,
        line: LineSpan,
        p: LinePrefix,
        style: DocumentStyle,
    ): EditResult {
        if (p.indent.isNotEmpty()) return IndentEdit.outdent(text, selection, style, FenceStates.NONE)
        return replace(text, line.start + p.quoteEnd, line.end, "")
    }

    private fun newline(
        text: CharSequence,
        selection: TextSelection,
        continuation: String,
    ): EditResult {
        val builder = EditBuilder(text)
        builder.replace(selection.start, selection.end, "\n" + continuation)
        return builder.build(TextSelection.caret(selection.start + 1 + continuation.length))
    }

    private fun replace(
        text: CharSequence,
        start: Int,
        end: Int,
        value: String,
    ): EditResult {
        val builder = EditBuilder(text)
        builder.replace(start, end, value)
        return builder.build(TextSelection.caret(start + value.length))
    }
}
