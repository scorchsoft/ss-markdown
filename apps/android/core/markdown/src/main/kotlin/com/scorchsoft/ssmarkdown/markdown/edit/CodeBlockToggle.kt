package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Wraps the selected lines in a fenced code block, or unwraps them when
 * they already are one: either the fences sit just outside the selection,
 * or the selection starts and ends on them.
 */
internal object CodeBlockToggle {
    private const val MIN_FENCE = 3

    fun apply(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
    ): EditResult {
        val lines = TextLines.linesIn(text, selection)
        val builder = EditBuilder(text)
        val first = lines.first()
        val last = lines.last()
        val above = TextLines.previous(text, first)
        val below = TextLines.next(text, last)
        when {
            lines.size == 2 && closes(first, last) -> {
                builder.delete(first.start, last.end)
            }

            lines.size > 2 && closes(first, last) -> {
                builder.delete(last.start - 1, last.end)
                builder.delete(first.start, lines[1].start)
            }

            above != null && below != null && closes(above, below) -> {
                builder.delete(last.end, below.end)
                builder.delete(above.start, first.start)
            }

            else -> {
                return wrap(text, selection, lines, style)
            }
        }
        return builder.buildMapping(selection, caretSticksAfter = false)
    }

    private fun wrap(
        text: CharSequence,
        selection: TextSelection,
        lines: List<LineSpan>,
        style: DocumentStyle,
    ): EditResult {
        val fence = fenceFor(lines, style.codeFence)
        val builder = EditBuilder(text)
        val first = lines.first()
        val last = lines.last()
        if (lines.size == 1 && first.content.isEmpty()) {
            builder.insert(first.start, "$fence\n\n$fence")
            return builder.build(TextSelection.caret(first.start + fence.length + 1))
        }
        builder.insert(last.end, "\n$fence")
        builder.insert(first.start, "$fence\n")
        // The selection moves inside the fences: its start past the opener, its end before the closer.
        val start = builder.map(selection.start, stickAfter = true)
        val end = if (selection.isCaret) start else maxOf(start, builder.map(selection.end, stickAfter = false))
        return builder.build(TextSelection(start, end))
    }

    /** A fence longer than any run of its char in the content, so the content can never close it early. */
    private fun fenceFor(
        lines: List<LineSpan>,
        preferred: String,
    ): String {
        val c = preferred[0]
        var longest = 0
        for (line in lines) {
            var run = 0
            for (ch in line.content) {
                run = if (ch == c) run + 1 else 0
                longest = maxOf(longest, run)
            }
        }
        return c.toString().repeat(maxOf(MIN_FENCE, longest + 1))
    }

    /** True when [open] is an opening fence that [close] closes. */
    private fun closes(
        open: LineSpan,
        close: LineSpan,
    ): Boolean {
        val opener = fenceRun(open.content)
        val closer = fenceRun(close.content)
        val matches = opener != null && closer != null && closer[0] == opener[0] && closer.length >= opener.length
        return matches && close.content.trim().length == closer?.length
    }

    private fun fenceRun(line: String): String? {
        val trimmed = line.trimStart(' ')
        val run = trimmed.takeWhile { it == trimmed.firstOrNull() }
        val indented = line.length - trimmed.length > MIN_FENCE
        return run.takeIf { !indented && run.length >= MIN_FENCE && run[0] in "`~" }
    }
}
