package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * A span of one [InlineFormat] on a line: content `[contentStart,
 * contentEnd)` with a delimiter of `width` chars hugging each side.
 */
internal class InlineSpan(
    val contentStart: Int,
    val contentEnd: Int,
    val width: Int,
) {
    val openStart: Int get() = contentStart - width
    val closeEnd: Int get() = contentEnd + width
}

/**
 * Finds the spans of a format on one line. A deliberately small cousin of
 * CommonMark's delimiter algorithm: runs pair up left to right, a run can
 * open when text follows it and close when text precedes it, and code
 * spans hide what is inside them. That covers what people write by hand
 * and what [InlineToggle] writes; a full parse would cost a block-level
 * view the strip does not need.
 */
internal object InlineSpans {
    private class Run(
        val start: Int,
        val end: Int,
    )

    /** Every span of [format] on [line], in order. */
    fun find(
        line: String,
        format: InlineFormat,
    ): List<InlineSpan> {
        val hidden = if (format == InlineFormat.Code) emptyList() else codeSpans(line)
        val spans = ArrayList<InlineSpan>()
        for (c in format.delimiterChars) {
            val candidates =
                runs(line, c).filter { run ->
                    format.fitsRun(run.end - run.start) &&
                        hidden.none { run.start >= it.openStart && run.end <= it.closeEnd }
                }
            var open: Run? = null
            for (run in candidates) {
                val opener = open
                if (opener != null && canClose(line, run, c)) {
                    spans += InlineSpan(opener.end, run.start, format.width)
                    open = null
                } else if (canOpen(line, run, c)) {
                    open = run
                }
            }
        }
        return spans.sortedBy { it.contentStart }
    }

    /** A run opens when text follows it; `_` never works inside a word (snake_case_names), `*` does. */
    private fun canOpen(
        line: String,
        run: Run,
        c: Char,
    ): Boolean {
        val before = line.getOrElse(run.start - 1) { ' ' }
        val after = line.getOrElse(run.end) { ' ' }
        return !after.isWhitespace() && !(c == '_' && before.isLetterOrDigit())
    }

    /** A run closes when text precedes it, with the same rule for `_` inside words. */
    private fun canClose(
        line: String,
        run: Run,
        c: Char,
    ): Boolean {
        val before = line.getOrElse(run.start - 1) { ' ' }
        val after = line.getOrElse(run.end) { ' ' }
        return !before.isWhitespace() && !(c == '_' && after.isLetterOrDigit())
    }

    /**
     * The span of [format] that encloses `[from, to)` on [line], if any. A
     * caret must sit inside the delimiters; a selection may take them in
     * too, as long as it stays within the span.
     */
    fun enclosing(
        line: String,
        format: InlineFormat,
        from: Int,
        to: Int,
    ): InlineSpan? =
        find(line, format).firstOrNull { span ->
            if (from == to) {
                from in span.contentStart..span.contentEnd
            } else {
                from >= span.openStart && to <= span.closeEnd && from <= span.contentEnd && to >= span.contentStart
            }
        }

    private fun codeSpans(line: String): List<InlineSpan> {
        val spans = ArrayList<InlineSpan>()
        val runs = runs(line, '`')
        var i = 0
        while (i < runs.size) {
            val open = runs[i]
            val width = open.end - open.start
            val close = runs.drop(i + 1).indexOfFirst { it.end - it.start == width }
            if (close < 0) {
                i++
                continue
            }
            val closer = runs[i + 1 + close]
            spans += InlineSpan(open.end, closer.start, width)
            i += close + 2
        }
        return spans
    }

    private fun runs(
        line: String,
        c: Char,
    ): List<Run> {
        val runs = ArrayList<Run>()
        var i = 0
        while (i < line.length) {
            if (line[i] != c) {
                i++
                continue
            }
            var end = i
            while (end < line.length && line[end] == c) end++
            runs += Run(i, end)
            i = end
        }
        return runs
    }
}
