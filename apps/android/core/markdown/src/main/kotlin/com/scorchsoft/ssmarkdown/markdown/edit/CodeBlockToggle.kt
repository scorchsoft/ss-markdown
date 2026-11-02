package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Wraps the selected lines in a fenced code block, or unwraps the block
 * the selection is in. "In a block" is the tokenizer's fence state
 * ([FenceStates]), not "a fence-looking line is adjacent": between two
 * blocks, the closing fence above and the opening fence below are not a
 * block, and a caret anywhere in a long block unwraps that block.
 */
internal object CodeBlockToggle {
    private const val MIN_FENCE = 3

    fun apply(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        fences: FenceStates = FenceStates.scan(text),
    ): EditResult {
        val lines = TextLines.linesIn(text, selection)
        val block = enclosingBlock(text, lines.first(), fences)
        if (block == null || block.close?.let { lines.last().start > it.start } == true) {
            return wrap(text, selection, lines, style)
        }
        val builder = EditBuilder(text)
        val close = block.close
        when {
            close == null && block.open.end >= text.length -> {
                builder.delete(maxOf(0, block.open.start - 1), block.open.end)
            }

            close == null -> {
                builder.delete(block.open.start, block.open.end + 1)
            }

            close.start == block.open.end + 1 -> {
                builder.delete(block.open.start, close.end)
            }

            else -> {
                builder.delete(close.start - 1, close.end)
                builder.delete(block.open.start, block.open.end + 1)
            }
        }
        return builder.buildMapping(selection, caretSticksAfter = false)
    }

    /** A fenced block's fence lines; [close] is null for a block left open to the end of the text. */
    internal class Block(
        val open: LineSpan,
        val close: LineSpan?,
    )

    /** The fenced block holding [line], found by walking to its fences, or null when [line] is not code. */
    fun enclosingBlock(
        text: CharSequence,
        line: LineSpan,
        fences: FenceStates,
    ): Block? {
        var open: LineSpan? = line
        var role = fences.roleAt(line.start)
        while (role == FenceRole.INSIDE || role == FenceRole.CLOSING_FENCE) {
            open = open?.let { TextLines.previous(text, it) }
            role = open?.let { fences.roleAt(it.start) } ?: FenceRole.OUTSIDE
        }
        val opener = open?.takeIf { role == FenceRole.OPENING_FENCE }
        return opener?.let { Block(it, closerOf(text, it, fences)) }
    }

    /** The fence closing the block [open] opens, or null when it runs to the end of the text. */
    private fun closerOf(
        text: CharSequence,
        open: LineSpan,
        fences: FenceStates,
    ): LineSpan? {
        var close = TextLines.next(text, open)
        while (close != null && fences.roleAt(close.start) == FenceRole.INSIDE) close = TextLines.next(text, close)
        return close?.takeIf { fences.roleAt(it.start) == FenceRole.CLOSING_FENCE }
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
}
