package com.scorchsoft.ssmarkdown.markdown.edit

/** Tallies the evidence for each [DocumentStyle] choice; one pass over the scanned lines. */
internal class StyleDetector {
    private val emphasis = HashMap<Char, Int>()
    private val strong = HashMap<Char, Int>()
    private val bullets = HashMap<Char, Int>()
    private val delimiters = HashMap<Char, Int>()
    private val fences = HashMap<String, Int>()
    private var paddedTableLines = 0
    private var compactTableLines = 0
    private var tabIndents = 0
    private var spaceIndents = 0
    private var narrowestSpaceIndent = Int.MAX_VALUE
    private var openFence: String? = null

    fun scan(
        text: CharSequence,
        limit: Int,
    ) {
        var start = 0
        while (start <= limit) {
            var end = start
            while (end < text.length && text[end] != '\n') end++
            line(text.subSequence(start, end).toString())
            start = end + 1
        }
    }

    fun result(): DocumentStyle {
        val d = DocumentStyle.DEFAULT
        return DocumentStyle(
            emphasis = emphasis.winner(d.emphasis),
            strong = strong.winner(d.strong),
            bullet = bullets.winner(d.bullet),
            orderedDelimiter = delimiters.winner(d.orderedDelimiter),
            indentUnit = indentUnit(d.indentUnit),
            codeFence = fences.winner(d.codeFence),
            paddedTables = compactTableLines <= paddedTableLines,
        )
    }

    private fun line(line: String) {
        val fence = fenceOf(line)
        val open = openFence
        if (open != null) {
            if (fence != null && fence[0] == open[0] && fence.length >= open.length) openFence = null
            return
        }
        if (fence != null) {
            fences.bump(fence.take(MIN_FENCE))
            openFence = fence
            return
        }
        val prefix = LinePrefix.parse(line)
        when (val marker = prefix.marker) {
            is ListMarker.Bullet -> bullets.bump(marker.char)
            is ListMarker.Ordered -> delimiters.bump(marker.delimiter)
            null -> Unit
        }
        indentation(prefix)
        table(prefix.content.trimEnd())
        delimiterRuns(line, prefix.contentStart)
    }

    private fun indentation(prefix: LinePrefix) {
        val indent = prefix.indent
        if (indent.isEmpty() || prefix.content.isBlank()) return
        if (indent[0] == '\t') {
            tabIndents++
        } else {
            spaceIndents++
            if (!prefix.isListItem) narrowestSpaceIndent = minOf(narrowestSpaceIndent, indent.length)
        }
    }

    private fun table(content: String) {
        if (content.length < 2 || content[0] != '|' || content.indexOf('|', 1) < 0) return
        if (content[1] == ' ') paddedTableLines++ else compactTableLines++
    }

    /** Counts left-flanking runs of `*` and `_` outside code: openers, which is what a writer chose. */
    private fun delimiterRuns(
        line: String,
        from: Int,
    ) {
        var i = from
        var inCode = false
        while (i < line.length) {
            val c = line[i]
            var end = i + 1
            if (c == '`') inCode = !inCode
            if (!inCode && c in DocumentStyle.EMPHASIS_CHARS) {
                while (end < line.length && line[end] == c) end++
                count(line, i, end)
            }
            i = end
        }
    }

    /** Tallies the run `line[start, end)` when it opens a span: text follows, and no word precedes it. */
    private fun count(
        line: String,
        start: Int,
        end: Int,
    ) {
        val before = line.getOrElse(start - 1) { ' ' }
        val opens = end < line.length && !line[end].isWhitespace() && !before.isLetterOrDigit()
        val c = line[start]
        if (opens && InlineFormat.Italic.fitsRun(end - start)) emphasis.bump(c)
        if (opens && InlineFormat.Bold.fitsRun(end - start)) strong.bump(c)
    }

    private fun indentUnit(default: String): String =
        when {
            tabIndents > spaceIndents -> "\t"
            narrowestSpaceIndent == 2 -> "  "
            else -> default
        }

    private companion object {
        const val MIN_FENCE = 3
        const val MAX_FENCE_INDENT = 3

        fun fenceOf(line: String): String? {
            val trimmed = line.trimStart(' ')
            val indented = line.length - trimmed.length > MAX_FENCE_INDENT
            val run = trimmed.takeWhile { it == trimmed.firstOrNull() }
            return run.takeIf { !indented && run.length >= MIN_FENCE && run[0] in "`~" }
        }

        fun <K> HashMap<K, Int>.bump(key: K) {
            this[key] = (this[key] ?: 0) + 1
        }

        /** The most used key, or [default] unless something beats it outright. */
        fun <K> HashMap<K, Int>.winner(default: K): K {
            val best = maxByOrNull { it.value } ?: return default
            return if (best.value > (this[default] ?: 0)) best.key else default
        }
    }
}
