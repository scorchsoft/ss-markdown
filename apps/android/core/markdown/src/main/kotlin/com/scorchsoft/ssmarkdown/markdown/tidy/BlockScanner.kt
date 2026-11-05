package com.scorchsoft.ssmarkdown.markdown.tidy

import com.scorchsoft.ssmarkdown.markdown.tidy.TidyGrammar.CODE_INDENT

/**
 * Sorts every line of a document into a [LineClass], in one pass from the
 * top, following markdown-it's block rules in their order: front matter,
 * tables, indented code, fences, quotes, maths, rules, lists, HTML,
 * headings, then paragraphs.
 *
 * Only the top level is read closely. Lists and quotes are walked as
 * regions ([ListRegion], [QuoteRegion]) that end where markdown-it ends
 * them, and whatever Tidy cannot be sure of is frozen up to the next
 * point where the top level is certain again ([freeze]). A frozen line is
 * never edited, so a wrong guess costs a missed fix, never a change to how
 * the document renders.
 */
@Suppress("TooManyFunctions") // One small function per block rule; together they are the top-level grammar.
internal class BlockScanner(
    private val lines: List<String>,
) {
    val classes: Array<LineClass> = Array(lines.size) { LineClass.TEXT }

    /** The fence to append when a top-level code block is still open at the end of the file. */
    var unclosedFence: TidyGrammar.Fence? = null
        private set

    val renumbers: MutableList<Renumber> = mutableListOf()

    /**
     * True when the file ends inside maths, raw HTML, front matter or a
     * frozen stretch that never closed. Even a final line break would then
     * change the block's content, so the end is left alone.
     */
    var endsOpen: Boolean = false
        private set

    private var inParagraph = false

    /** The paragraph above may really be a definition, after which markdown-it has no paragraph open. */
    private var paragraphUnsure = false

    fun scan(): BlockScanner {
        var i = if (lines.isNotEmpty() && TidyGrammar.frontMatterDashes(lines[0]) > 0) frontMatter() else 0
        while (i < lines.size) i = step(i)
        return this
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // markdown-it's rule order: the first rule that matches wins.
    private fun step(i: Int): Int {
        val line = lines[i]
        if (TidyGrammar.isBlank(line)) return mark(i, LineClass.BLANK)
        val col = TidyGrammar.indent(line)
        if (inParagraph) {
            if (col >= CODE_INDENT) return markKeep(i)
            if (TidyGrammar.isSetextUnderline(line)) {
                return if (paragraphUnsure) freeze(i) else mark(i, LineClass.SETEXT)
            }
        }
        if (TidyGrammar.isTableStart(lines, i)) return table(i)
        if (col >= CODE_INDENT) return mark(i, LineClass.FROZEN)
        TidyGrammar.fenceOpen(line)?.let { return fence(i, it) }
        if (TidyGrammar.isQuote(line)) return quote(i)
        if (TidyGrammar.isMathStart(line)) return math(i)
        if (TidyGrammar.isHr(line)) return mark(i, LineClass.BREAK)
        TidyGrammar.listMarker(line)?.let { marker ->
            if (!inParagraph || marker.canInterrupt) return list(i)
            return if (paragraphUnsure) freeze(i) else markKeep(i, LineClass.TEXT)
        }
        val html = TidyGrammar.htmlType(line)
        when {
            html in 1..TidyGrammar.HTML_RAW_LAST -> return htmlRaw(i, html)
            html == TidyGrammar.HTML_BLOCK_TAG || (html > 0 && !inParagraph) -> return htmlRun(i)
            html != 0 -> return freeze(i)
        }
        if (TidyGrammar.isAtxHeading(line)) return mark(i, LineClass.HEADING)
        // Definition lists and footnotes are containers Tidy does not model.
        if (TidyGrammar.isDefinitionMarker(line) || TidyGrammar.isFootnote(line)) return freeze(i)
        // After a definition markdown-it may have no paragraph open (a footnote may even interrupt one).
        paragraphUnsure = (inParagraph && paragraphUnsure) || TidyGrammar.isDefinitionLike(line)
        inParagraph = true
        classes[i] = LineClass.TEXT
        return i + 1
    }

    /** Marks line [i] as a block of its own: no paragraph is open after it. */
    private fun mark(
        i: Int,
        cls: LineClass,
    ): Int {
        classes[i] = cls
        inParagraph = false
        paragraphUnsure = false
        return i + 1
    }

    /** Marks line [i] as a continuation of the open paragraph. */
    private fun markKeep(
        i: Int,
        cls: LineClass = LineClass.FROZEN,
    ): Int {
        classes[i] = cls
        return i + 1
    }

    private fun frozen(
        from: Int,
        to: Int,
    ) {
        for (j in from until to) {
            classes[j] = if (TidyGrammar.isBlank(lines[j])) LineClass.FROZEN_BLANK else LineClass.FROZEN
        }
        inParagraph = false
        paragraphUnsure = false
    }

    /** Front matter: the first line's dashes, to a line of as many dashes, or a `...` line, or the end. */
    private fun frontMatter(): Int {
        val dashes = TidyGrammar.frontMatterDashes(lines[0])

        // The plugin also stops after a `...` line (it tests the line above before each line).
        fun dots(j: Int) = j > 1 && lines[j - 1].trimStart(' ', '\t') == "..."
        val closer =
            (1 until lines.size).firstOrNull { j ->
                dots(j) || TidyGrammar.closesFrontMatter(lines[j], dashes)
            }
        val end =
            when {
                closer == null -> lines.size
                dots(closer) -> closer
                else -> closer + 1
            }
        frozen(0, end)
        endsOpen = end == lines.size && !TidyGrammar.closesFrontMatter(lines[end - 1], dashes)
        return end
    }

    private fun table(i: Int): Int {
        var j = i + 2
        while (j < lines.size && !TidyGrammar.endsTable(lines[j])) j++
        frozen(i, j)
        return j
    }

    private fun fence(
        i: Int,
        fence: TidyGrammar.Fence,
    ): Int {
        var j = i + 1
        while (j < lines.size && !TidyGrammar.closes(lines[j], fence)) j++
        frozen(i + 1, j)
        classes[i] = LineClass.FENCE_OPEN
        if (j == lines.size) {
            unclosedFence = fence
            return j
        }
        classes[j] = LineClass.FENCE_CLOSE
        return j + 1
    }

    private fun math(i: Int): Int {
        var j = i
        if (!TidyGrammar.isMathOneLine(lines[i])) {
            j++
            while (j < lines.size && !TidyGrammar.closesMath(lines[j])) j++
        }
        endsOpen = j >= lines.size
        val end = minOf(j + 1, lines.size)
        frozen(i, end)
        return end
    }

    private fun htmlRaw(
        i: Int,
        type: Int,
    ): Int {
        var j = i
        while (j < lines.size && !TidyGrammar.endsHtml(type, lines[j])) j++
        endsOpen = j >= lines.size
        val end = minOf(j + 1, lines.size)
        frozen(i, end)
        return end
    }

    /** HTML types 6 and 7 run to the next blank line. */
    private fun htmlRun(i: Int): Int {
        var j = i
        while (j < lines.size && !TidyGrammar.isBlank(lines[j])) j++
        frozen(i, j)
        endsOpen = j == lines.size
        return j
    }

    private fun quote(i: Int): Int {
        val (end, kind) = QuoteRegion.walk(lines, i)
        if (kind == RegionKind.UNSURE) return freeze(i)
        if (kind == RegionKind.COMPLEX) {
            frozen(i, end)
            endsOpen = end == lines.size
        } else {
            for (j in i until end) classes[j] = LineClass.QUOTE
        }
        inParagraph = false
        paragraphUnsure = false
        return end
    }

    private fun list(i: Int): Int {
        val walk = ListRegion(lines, i).walk()
        when (walk.kind) {
            RegionKind.UNSURE -> {
                return freeze(i)
            }

            RegionKind.COMPLEX -> {
                // Its trailing blank lines may be an open fence's code: frozen with it.
                frozen(i, walk.end)
                endsOpen = walk.end == lines.size
                return walk.end
            }

            RegionKind.SIMPLE -> {
                for (j in i..walk.lastText) {
                    classes[j] = if (TidyGrammar.isBlank(lines[j])) LineClass.BLANK else LineClass.LIST
                }
                renumbers += walk.renumbers
            }
        }
        inParagraph = false
        paragraphUnsure = false
        return walk.lastText + 1
    }

    /**
     * Freezes from line [i] to the next line that is certainly at the top
     * level: a non-blank line at column 0 after a blank line ends every
     * paragraph, list, quote, definition and footnote, so markdown-it reads
     * it afresh. Only fences, maths and raw HTML carry on past it; if any
     * line on the way could hold one, the rest of the document is frozen.
     */
    private fun resumesAt(
        from: Int,
        j: Int,
    ): Boolean {
        val line = lines[j]
        val afterBlank = j > from && TidyGrammar.isBlank(lines[j - 1])
        return afterBlank && TidyGrammar.indent(line) == 0 && !TidyGrammar.isBlank(line) &&
            !TidyGrammar.isDefinitionMarker(line)
    }

    private fun freeze(i: Int): Int {
        val stop = (i until lines.size).firstOrNull { j -> TidyGrammar.mayRunAcrossBlanks(lines[j]) || resumesAt(i, j) }
        val end = if (stop == null || TidyGrammar.mayRunAcrossBlanks(lines[stop])) lines.size else stop
        frozen(i, end)
        endsOpen = end == lines.size
        return end
    }
}
