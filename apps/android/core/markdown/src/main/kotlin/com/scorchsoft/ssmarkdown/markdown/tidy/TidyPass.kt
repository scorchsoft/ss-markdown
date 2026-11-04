package com.scorchsoft.ssmarkdown.markdown.tidy

/**
 * One run of the rules over one text: classify the lines ([BlockScanner]),
 * then let each rule add small edits in original offsets. Each rule only
 * ever touches lines whose class says it is safe to, and [MarkdownTidy]
 * then sorts, merges and checks the edits.
 */
internal class TidyPass(
    text: String,
    private val options: TidyOptions = TidyOptions(),
) {
    private val doc = TidyDoc(text)
    private val classes = doc.classes
    private val lines = doc.lines

    val edits: List<TidyEdit> get() = doc.edits
    val counts: Map<TidyRule, Int> get() = doc.counts

    /** The closing fence appended for an open code block, if any. */
    var appendedFence: String? = null
        private set

    fun run(): TidyPass {
        freezeHtmlRuns()
        val blank = BlankLineRules(doc, options.collapseBlankLines)
        blank.around()
        blank.runs()
        TrailingSpaceRule(doc).apply()
        headingSpacing()
        for (r in doc.scanner.renumbers) {
            if (classes[r.line] != LineClass.LIST || (r.repeated && !options.countUpRepeatedLists)) continue
            doc.edit(doc.starts[r.line] + r.start, doc.starts[r.line] + r.end, r.digits, TidyRule.ListNumbering)
        }
        if (lines.isNotEmpty() && !doc.scanner.endsOpen) endOfFile()
        return this
    }

    /** A paragraph with a line that starts with `<` may be HTML; the whole run is left alone. */
    private fun freezeHtmlRuns() {
        var i = 0
        while (i < lines.size) {
            var j = i
            var html = false
            while (j < lines.size && isLoose(j)) {
                if (lines[j].getOrNull(TidyGrammar.firstText(lines[j])) == '<') html = true
                j++
            }
            if (html) for (k in i until j) classes[k] = LineClass.FROZEN
            i = maxOf(j, i + 1)
        }
    }

    private fun isLoose(i: Int): Boolean =
        classes[i] == LineClass.TEXT || classes[i] == LineClass.HEADING || classes[i] == LineClass.SETEXT ||
            classes[i] == LineClass.BREAK

    private fun headingSpacing() {
        for (i in lines.indices) {
            if (classes[i] != LineClass.HEADING) continue
            val line = lines[i]
            var p = TidyGrammar.firstText(line)
            while (p < line.length && line[p] == '#') p++
            var q = p
            while (q < line.length && line[q] == ' ') q++
            val rest = line.substring(q).trim()
            // `#   #` is an empty heading with a closing sequence: left alone.
            if (q - p >= 2 && rest.any { it != '#' }) {
                doc.edit(doc.starts[i] + p + 1, doc.starts[i] + q, "", TidyRule.HeadingSpacing)
            }
        }
    }

    /**
     * Exactly one line break at the end: blank lines there go (unless they
     * are an open block's content), a missing break is added, and an open
     * top-level fence is closed.
     */
    private fun endOfFile() {
        val length = doc.text.length
        var last = lines.size - 1
        while (last >= 0 && classes[last].isBlank) last--
        val trailingFree = (last + 1 until lines.size).all { classes[it] == LineClass.BLANK }
        val fence = doc.scanner.unclosedFence
        when {
            last < 0 -> {
                if (trailingFree) doc.edit(0, length, "", TidyRule.ExtraBlankLines)
            }

            last + 1 < lines.size && trailingFree -> {
                doc.edit(doc.starts[last + 1], length, "", TidyRule.FinalNewline)
            }

            fence != null -> {
                closeFence(fence)
            }

            // A frozen last line may be HTML, whose content would gain the line break.
            !doc.endsWithNewline && classes[lines.size - 1] != LineClass.FROZEN -> {
                doc.edit(length, length, "\n", TidyRule.FinalNewline)
            }
        }
    }

    private fun closeFence(fence: TidyGrammar.Fence) {
        val length = doc.text.length
        appendedFence = fence.closer
        val last = lines.size - 1
        when {
            doc.endsWithNewline -> {
                doc.edit(length, length, fence.closer + "\n", TidyRule.UnclosedFence)
            }

            // markdown-it drops a last line of only spaces with no line break; so does the closed block.
            TidyGrammar.isBlank(lines[last]) && last > 0 -> {
                doc.edit(doc.starts[last], length, fence.closer + "\n", TidyRule.UnclosedFence)
            }

            else -> {
                doc.edit(length, length, "\n", TidyRule.FinalNewline)
                doc.edit(length, length, fence.closer + "\n", TidyRule.UnclosedFence)
            }
        }
    }
}
