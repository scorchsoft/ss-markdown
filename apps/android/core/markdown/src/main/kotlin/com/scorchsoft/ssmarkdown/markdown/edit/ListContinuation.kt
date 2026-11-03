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
@Suppress("TooManyFunctions") // Enter's cases (item, empty item, quote, code) and the commands that share them.
internal object ListContinuation {
    /** How far up Enter looks for the item an indented line continues. */
    private const val MAX_CONTINUATION_LINES = 100

    fun apply(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        fences: FenceStates = FenceStates.NONE,
    ): EditResult {
        val line = TextLines.lineAt(text, selection.start)
        val column = selection.start - line.start
        val p = LinePrefix.parse(line.content)
        val role = fences.roleAt(line.start)
        if (role == FenceRole.INSIDE && p.quoteEnd == 0) {
            // `- ` in code is code: keep the indentation (one unit more after `{`), never continue a "list".
            return newline(text, selection, CodeIndent.newlineIndent(text, line, column, fences))
        }
        if (role.isCode) {
            val keep = if (column >= p.indentEnd) p.quote + p.indent else ""
            return newline(text, selection, keep)
        }
        return prose(text, selection, style, line, p)
    }

    @Suppress("CyclomaticComplexMethod") // The table of Enter's cases, one line each.
    private fun prose(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        line: LineSpan,
        p: LinePrefix,
    ): EditResult {
        val column = selection.start - line.start
        val emptyItem = selection.isCaret && p.content.isBlank() && column >= p.contentStart
        val emptyQuote = p.quoteEnd > 0 && !p.isListItem && p.indent.isEmpty() && emptyItem
        val owner = if (p.isListItem || column < p.indentEnd) null else owningItem(text, line, p)
        return when {
            p.isListItem && emptyItem -> endItem(text, selection, line, p, style)
            p.isListItem && column >= p.contentStart -> nextItem(text, selection, line, p)
            emptyQuote && column >= p.quoteEnd -> endQuote(text, line, p)
            owner != null -> nextItem(text, selection, owner.first, owner.second)
            column >= p.indentEnd -> newline(text, selection, p.quote + p.indent)
            else -> newline(text, selection, "")
        }
    }

    /**
     * The item [line] continues, when it is an indented line of the item's
     * first paragraph after a hard break (`\` or two trailing spaces): the
     * caret is at the end of the item, so Enter starts the next item, as on
     * the item's first line. Null for any other line, so an indented
     * paragraph of an item just keeps its indentation (EB-LIST-11).
     */
    @Suppress("ReturnCount") // Each line walked can settle it.
    private fun owningItem(
        text: CharSequence,
        line: LineSpan,
        p: LinePrefix,
    ): Pair<LineSpan, LinePrefix>? {
        if (line.isBlank || p.indentWidth == 0) return null
        var current = TextLines.previous(text, line)
        var walked = 0
        while (current != null && !current.isBlank && walked < MAX_CONTINUATION_LINES) {
            if (!endsInHardBreak(current.content)) return null
            val above = LinePrefix.parse(current.content)
            if (above.quote != p.quote) return null
            if (above.isListItem) {
                val column = above.line.substring(above.quoteEnd, above.bodyStart).length
                return if (p.indentWidth >= column) current to above else null
            }
            if (above.indentWidth == 0) return null
            current = TextLines.previous(text, current)
            walked++
        }
        return null
    }

    private fun endsInHardBreak(content: String): Boolean = content.endsWith("\\") || content.endsWith("  ")

    /**
     * Enter on an empty quote line: the quote ends there. The `>` goes, and
     * a blank line keeps what is typed next from joining the quote above
     * (a lazy continuation, CommonMark); a bare `>` line just above is
     * taken in, so no empty quote line is left behind.
     */
    private fun endQuote(
        text: CharSequence,
        line: LineSpan,
        p: LinePrefix,
    ): EditResult {
        val levels = p.quote.count { it == '>' }
        if (levels > 1) {
            // A nested quote steps out one level first (EB-QUOTE-04).
            val outer = p.quote.substring(0, p.quote.trimEnd().lastIndexOf('>')).trimEnd() + " "
            return replace(text, line.start, line.end, outer)
        }
        val above = TextLines.previous(text, line)
        val abovePrefix = above?.let { LinePrefix.parse(it.content) }
        val bareAbove =
            abovePrefix != null && abovePrefix.quoteEnd > 0 && above.content.substring(abovePrefix.quoteEnd).isBlank()
        return when {
            bareAbove -> replace(text, above.start, line.end, "\n")
            above != null && !above.isBlank -> replace(text, line.start, line.end, "\n")
            else -> replace(text, line.start, line.end, "")
        }
    }

    private fun nextItem(
        text: CharSequence,
        selection: TextSelection,
        line: LineSpan,
        p: LinePrefix,
    ): EditResult {
        val marker = p.marker
        val lazy = marker is ListMarker.Ordered && Renumbering.isLazy(text, line, p, marker)
        val next =
            when (marker) {
                is ListMarker.Ordered -> "${if (lazy) marker.number else marker.number + 1}${marker.delimiter}"
                is ListMarker.Bullet -> marker.char.toString()
                null -> ""
            }
        val task = if (p.isTask) "[ ] " else ""
        val continuation = p.quote + p.indent + next + p.markerSpacing + task
        val builder = EditBuilder(text)
        builder.replace(selection.start, selection.end, "\n" + continuation)
        if (marker is ListMarker.Ordered && !lazy) {
            // The items after the selection's last line follow the new one.
            val last = TextLines.lineAt(text, selection.end)
            Renumbering.after(text, builder, last, p, marker.number + 2)
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
        return exitList(text, line, p, null)
    }

    /**
     * Outdent on one top-level list item, which has no level left to step
     * out of: [exitList], with the selection kept on its text. Null when the
     * selection is anything else, for [IndentEdit.outdent] to handle.
     */
    fun outOfTopLevelItem(
        text: CharSequence,
        selection: TextSelection,
        fences: FenceStates,
    ): EditResult? {
        val line = TextLines.targetLines(text, selection).singleOrNull() ?: return null
        val p = LinePrefix.parse(line.content)
        val topLevel = p.isListItem && p.indent.isEmpty()
        return if (topLevel && !fences.roleAt(line.start).isCode) exitList(text, line, p, selection) else null
    }

    /**
     * Backspace at the very start of an item's text (docs/editing-behaviours.md
     * D2): the marker comes off and the text stays where it is, a plain
     * paragraph line ([exitList]). A task loses only its box first, so it is
     * a bullet again (EB-TASK-05). Null when the caret is anywhere else.
     */
    fun unwrapItem(
        text: CharSequence,
        selection: TextSelection,
        fences: FenceStates,
    ): EditResult? {
        if (!selection.isCaret) return null
        val line = TextLines.lineAt(text, selection.start)
        val p = LinePrefix.parse(line.content)
        val atStart = p.isListItem && selection.start - line.start == p.contentStart
        if (!atStart || fences.roleAt(line.start).isCode) return null
        if (p.isTask) {
            val builder = EditBuilder(text)
            builder.delete(line.start + p.bodyStart, line.start + p.contentStart)
            return builder.buildMapping(selection)
        }
        return exitList(text, line, p, selection)
    }

    /**
     * Turns the item on [line] into a plain line, its text kept. Right under
     * another line of the list, a bare line would be a lazy continuation of
     * the item above (CommonMark), so a blank line (in the same quote) goes
     * between them first; under a paragraph line it rejoins that paragraph,
     * which is where a list key pressed twice started. The items after it become a list of their own; a
     * numbered one continues the count ([Renumbering.splitRest]). The caret
     * goes where the text starts, or [selection] follows the text it was on.
     */
    internal fun exitList(
        text: CharSequence,
        line: LineSpan,
        p: LinePrefix,
        selection: TextSelection?,
    ): EditResult {
        val above = TextLines.previous(text, line)
        // Only a line of the list above would swallow it; under a paragraph line it simply joins that paragraph again.
        val abovePrefix = above?.let { LinePrefix.parse(it.content) }
        val joined =
            abovePrefix != null && abovePrefix.content.isNotBlank() &&
                (abovePrefix.isListItem || abovePrefix.indent.isNotEmpty())
        val separator = if (joined) p.quote.trimEnd() + "\n" else ""
        val builder = EditBuilder(text)
        builder.replace(line.start, line.start + p.contentStart, separator + p.quote)
        val marker = p.marker
        if (marker is ListMarker.Ordered) Renumbering.splitRest(text, builder, line, p, marker.number)
        if (selection != null) return builder.buildMapping(selection)
        return builder.build(TextSelection.caret(line.start + separator.length + p.quoteEnd))
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
