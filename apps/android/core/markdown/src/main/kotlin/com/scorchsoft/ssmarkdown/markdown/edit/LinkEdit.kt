package com.scorchsoft.ssmarkdown.markdown.edit

/** An inline link already in the text: its label and destination as written (escapes kept). */
public data class ExistingLink(
    /** The text between `[` and `]`. */
    public val label: String,
    /** The destination, without `<>` and without a title. */
    public val url: String,
) {
    /** Reading links for the prompt. */
    public companion object {
        /** The inline link holding the whole [selection], or null (an image is not a link). */
        public fun at(
            text: CharSequence,
            selection: TextSelection,
        ): ExistingLink? = LinkEdit.existing(text, selection)

        /** True when [text] reads as an address (`https://`, `http://`, `mailto:`, `www.`). */
        public fun isUrl(text: String): Boolean = LinkEdit.isUrl(text)
    }
}

/**
 * The strip's Link prompt (docs/ux-spec.md, "Formatting strip"): finds the
 * inline link at the selection, writes one with a label and destination,
 * and takes one off again, keeping its text.
 */
internal object LinkEdit {
    private val urlLike = Regex("""^(https?://|mailto:|www\.)\S+$""", RegexOption.IGNORE_CASE)

    /** True when [text] reads as an address the prompt can prefill. */
    fun isUrl(text: String): Boolean = urlLike.matches(text.trim())

    /** The inline link (not an image) that holds the whole selection, or null. */
    fun find(
        text: CharSequence,
        selection: TextSelection,
    ): LinkSyntax.Span? {
        val line = TextLines.lineAt(text, selection.start)
        if (selection.end > line.end) return null
        val content = line.content
        var i = 0
        while (i < content.length) {
            val span = if (content[i] == '[' && !LinkSyntax.escaped(content, i)) LinkSyntax.spanAt(content, i) else null
            if (span != null) {
                val shifted = span.shifted(line.start)
                if (selection.start >= shifted.start && selection.end <= shifted.end) return shifted
                i = span.end
            } else {
                i++
            }
        }
        return null
    }

    /** The label and destination of the link at [selection], for the prompt to edit. */
    fun existing(
        text: CharSequence,
        selection: TextSelection,
    ): ExistingLink? =
        find(text, selection)?.let {
            ExistingLink(
                text.substring(it.label.first, it.label.last + 1),
                text.substring(it.url.first, it.url.last + 1),
            )
        }

    /**
     * Writes `[label](url)` over the link at the selection, or over the
     * selection. A blank [label] uses the address. A link's title is kept.
     * The caret lands after the link.
     */
    fun set(
        text: CharSequence,
        selection: TextSelection,
        label: String,
        url: String,
    ): EditResult {
        val target = find(text, selection)
        val shownLabel = LinkSyntax.escapeLabel(label.ifBlank { url.trim() })
        val written = "[$shownLabel](${LinkSyntax.destination(url.trim())}${target?.rest.orEmpty()})"
        val start = target?.start ?: selection.start
        val builder = EditBuilder(text)
        builder.replace(start, target?.end ?: selection.end, written)
        return builder.build(TextSelection.caret(start + written.length))
    }

    /** Takes the link at the selection off, keeping its label as plain text. */
    fun remove(
        text: CharSequence,
        selection: TextSelection,
    ): EditResult {
        val target = find(text, selection) ?: return EditResult.unchanged(selection)
        val builder = EditBuilder(text)
        builder.replace(target.start, target.end, text.substring(target.label.first, target.label.last + 1))
        return builder.build(TextSelection(target.start, target.start + target.label.last + 1 - target.label.first))
    }
}

/** Reading and writing inline link syntax on one line, by CommonMark's rules where the prompt needs them. */
internal object LinkSyntax {
    /** A link's columns on its line: `[label](destination "title")`. */
    class Span(
        val start: Int,
        val end: Int,
        /** The text between the brackets. */
        val label: IntRange,
        /** The destination, without `<>`. */
        val url: IntRange,
        /** Whatever follows the destination inside the parentheses (a title), kept as written. */
        val rest: String,
    ) {
        fun shifted(by: Int): Span =
            Span(
                start + by,
                end + by,
                label.first + by until label.last + 1 + by,
                url.first + by until url.last + 1 + by,
                rest,
            )
    }

    /** True when the char at [at] is escaped by an odd run of backslashes. */
    fun escaped(
        s: String,
        at: Int,
    ): Boolean {
        var n = 0
        var i = at - 1
        while (i >= 0 && s[i] == '\\') {
            n++
            i--
        }
        return n % 2 == 1
    }

    /** Brackets in a label must not end it early: an unescaped `[` or `]` gets a backslash. */
    fun escapeLabel(label: String): String {
        val out = StringBuilder()
        for ((i, c) in label.withIndex()) {
            if ((c == '[' || c == ']') && !escaped(label, i)) out.append('\\')
            out.append(c)
        }
        return out.toString()
    }

    /** A destination with spaces or unbalanced parentheses is wrapped in `<>`, which CommonMark reads whole. */
    fun destination(url: String): String {
        var depth = 0
        for (c in url) {
            if (c == '(') depth++
            if (c == ')') depth--
            if (depth < 0) break
        }
        val needsAngle = url.any { it.isWhitespace() } || depth != 0
        return if (needsAngle) "<${url.replace("<", "%3C").replace(">", "%3E")}>" else url
    }

    /** The link starting at `[` [open] in [s], or null when there is none (or it is an image). */
    fun spanAt(
        s: String,
        open: Int,
    ): Span? {
        if (open > 0 && s[open - 1] == '!' && !escaped(s, open - 1)) return null
        var depth = 0
        var close = -1
        var i = open
        while (i < s.length && close < 0) {
            when (s[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> if (--depth == 0) close = i
            }
            i++
        }
        if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return null
        return destinationAt(s, open, close)
    }

    /** The `(destination "title")` after the label that ends at [close]. */
    private fun destinationAt(
        s: String,
        open: Int,
        close: Int,
    ): Span? {
        var i = close + 2
        while (i < s.length && s[i] == ' ') i++
        val angled = i < s.length && s[i] == '<'
        val urlStart = if (angled) i + 1 else i
        val urlEnd = if (angled) s.indexOf('>', urlStart) else plainEnd(s, urlStart)
        if (urlEnd < 0) return null
        val restStart = if (angled) urlEnd + 1 else urlEnd
        val end = s.indexOf(')', restStart)
        if (end < 0) return null
        return Span(open, end + 1, open + 1 until close, urlStart until urlEnd, s.substring(restStart, end))
    }

    /** End of a bare destination: the first space, or the `)` that balances none opened inside it. */
    private fun plainEnd(
        s: String,
        from: Int,
    ): Int {
        var depth = 0
        var i = from
        while (i < s.length && !s[i].isWhitespace()) {
            val c = s[i]
            if (c == ')' && depth == 0) break
            when (c) {
                '\\' -> i++
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return minOf(i, s.length)
    }
}
