package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Bold, italic, strikethrough and inline code. One rule for every format,
 * which is also what the strip's pressed state shows (see [FormatState]):
 *
 * - inside a span of the format → the span's delimiters are removed;
 * - a caret elsewhere → an empty pair is inserted with the caret inside,
 *   and pressing again straight away takes the pair back out;
 * - a selection → each selected line's text is wrapped, with the
 *   delimiters hugging the words (`** x**` would not render, so edge
 *   spaces stay outside).
 */
internal object InlineToggle {
    /** The part of one line a selection covers, as columns. */
    private class Segment(
        val line: LineSpan,
        val from: Int,
        val to: Int,
    ) {
        fun enclosing(format: InlineFormat): InlineSpan? = InlineSpans.enclosing(line.content, format, from, to)
    }

    fun apply(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        format: InlineFormat,
    ): EditResult =
        if (selection.isCaret) {
            atCaret(text, selection.start, style, format)
        } else {
            overSelection(text, selection, style, format)
        }

    private fun atCaret(
        text: CharSequence,
        caret: Int,
        style: DocumentStyle,
        format: InlineFormat,
    ): EditResult {
        val line = TextLines.lineAt(text, caret)
        val column = caret - line.start
        val builder = EditBuilder(text)
        val span = InlineSpans.enclosing(line.content, format, column, column)
        val marker = format.marker(style)
        return when {
            isEmptyPair(line.content, column, format) -> {
                builder.delete(caret, caret + format.width)
                builder.delete(caret - format.width, caret)
                builder.build(TextSelection.caret(caret - format.width))
            }

            span != null -> {
                unwrap(builder, line, span)
                builder.buildMapping(TextSelection.caret(caret), caretSticksAfter = false)
            }

            else -> {
                builder.insert(caret, marker + marker)
                builder.build(TextSelection.caret(caret + marker.length))
            }
        }
    }

    private fun overSelection(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        format: InlineFormat,
    ): EditResult {
        val segments =
            TextLines
                .linesIn(text, selection)
                .map { line ->
                    Segment(
                        line,
                        maxOf(selection.start, line.start) - line.start,
                        minOf(selection.end, line.end) - line.start,
                    )
                }.filterNot {
                    it.line.content
                        .substring(it.from, it.to)
                        .isBlank()
                }
        val spans = segments.map { it.enclosing(format) }
        val builder = EditBuilder(text)
        return when {
            segments.isEmpty() -> {
                EditResult.unchanged(selection)
            }

            spans.all { it != null } -> {
                segments.forEachIndexed { i, segment -> unwrap(builder, segment.line, requireNotNull(spans[i])) }
                builder.buildMapping(selection, caretSticksAfter = false)
            }

            else -> {
                segments.forEachIndexed { i, segment -> if (spans[i] == null) wrap(builder, segment, style, format) }
                // The new selection hugs the words, as the markers do.
                var from = selection.start
                var to = selection.end
                while (text[from].isWhitespace()) from++
                while (text[to - 1].isWhitespace()) to--
                val start = builder.map(from, stickAfter = true)
                builder.build(TextSelection(start, maxOf(start, builder.map(to, stickAfter = false))))
            }
        }
    }

    private fun wrap(
        builder: EditBuilder,
        segment: Segment,
        style: DocumentStyle,
        format: InlineFormat,
    ) {
        val content = segment.line.content
        var start = segment.from
        var end = segment.to
        while (content[start].isWhitespace()) start++
        while (content[end - 1].isWhitespace()) end--
        var open = format.marker(style)
        var close = open
        if (format == InlineFormat.Code && content.substring(start, end).contains('`')) {
            // A backtick inside needs a longer fence, padded so it cannot touch the content's own.
            open = "`` "
            close = " ``"
        }
        builder.insert(segment.line.start + end, close)
        builder.insert(segment.line.start + start, open)
    }

    private fun unwrap(
        builder: EditBuilder,
        line: LineSpan,
        span: InlineSpan,
    ) {
        builder.delete(line.start + span.contentEnd, line.start + span.closeEnd)
        builder.delete(line.start + span.openStart, line.start + span.contentStart)
    }

    /** True for `**|**`: the pair a caret toggle just inserted, and nothing else in the run. */
    private fun isEmptyPair(
        line: String,
        column: Int,
        format: InlineFormat,
    ): Boolean {
        val w = format.width
        val fits = column >= w && column + w <= line.length
        val c = line.getOrNull(column)
        if (!fits || c == null || c !in format.delimiterChars) return false
        var start = column
        while (start > 0 && line[start - 1] == c) start--
        var end = column
        while (end < line.length && line[end] == c) end++
        return start == column - w && end == column + w
    }
}
