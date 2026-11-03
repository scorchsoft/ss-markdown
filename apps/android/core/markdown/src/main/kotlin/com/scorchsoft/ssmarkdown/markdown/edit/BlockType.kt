package com.scorchsoft.ssmarkdown.markdown.edit

/** The block types the strip's ¶ menu converts between (docs/ux-spec.md, "Formatting strip"). */
public enum class BlockType {
    /** Plain text. */
    Paragraph,

    /** `# ` */
    Heading1,

    /** `## ` */
    Heading2,

    /** `### ` */
    Heading3,

    /** `- item`, with the document's own bullet. */
    Bullet,

    /** `1. item`, with the document's own delimiter. */
    Numbered,

    /** `- [ ] item` */
    Task,

    /** `> text` */
    Quote,

    /** A fenced code block. */
    CodeBlock,
    ;

    /** Reading a line's type. */
    public companion object {
        private val headings = listOf(Heading1, Heading2, Heading3)

        /**
         * The type of the block at the start of [selection], for the menu's
         * tick, or null when it is none of these (a level 4 to 6 heading).
         */
        public fun at(
            text: CharSequence,
            selection: TextSelection,
            fences: FenceStates = FenceStates.NONE,
        ): BlockType? {
            require(selection.end <= text.length) { "selection $selection outside text of ${text.length}" }
            val line = TextLines.lineAt(text, selection.start)
            if (fences.roleAt(line.start).isCode) return CodeBlock
            val p = LinePrefix.parse(line.content)
            val (level, _) = HeadingEdit.parse(line.content.substring(p.quoteEnd))
            return when {
                p.isTask -> Task
                p.marker is ListMarker.Ordered -> Numbered
                p.marker is ListMarker.Bullet -> Bullet
                level > 0 -> headings.getOrNull(level - 1)
                p.quoteEnd > 0 -> Quote
                else -> Paragraph
            }
        }
    }
}

/**
 * Converts every block in the selection to one [BlockType], replacing the
 * block syntax each line had (quote, list marker, task box, heading
 * hashes, a fenced block's fences) and keeping its text, so a bullet
 * becomes a heading without its `- `, and a heading becomes a task with
 * `- [ ] `. Only the selected lines change, plus the blank lines and
 * fences the conversion needs:
 *
 * - A paragraph right under another block's text would be a lazy
 *   continuation of it (CommonMark), so a blank line goes between them,
 *   and between items that each become their own paragraph.
 * - Code: the lines are wrapped in a fence that their content cannot
 *   close. From code, the fences come off and each line converts.
 */
@Suppress("TooManyFunctions") // One conversion with its few helpers: each is a step of the ¶ menu's rule.
internal object BlockTypeEdit {
    private const val MIN_FENCE = 3
    private val HEADINGS = setOf(BlockType.Heading1, BlockType.Heading2, BlockType.Heading3)

    @Suppress("ReturnCount") // Code, a table, a code block and prose each end the dispatch.
    fun apply(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        type: BlockType,
        fences: FenceStates,
    ): EditResult {
        val first = TextLines.lineAt(text, selection.start)
        if (fences.roleAt(first.start).isCode) {
            if (type == BlockType.CodeBlock) return EditResult.unchanged(selection)
            return fromCode(text, selection, style, type, fences)
        }
        val lines = TextLines.linesIn(text, selection)
        // A table row cannot become a heading or a list item without breaking the table: ¶ leaves tables alone.
        if (lines.any { TableLines.isTableLine(text, it) }) return EditResult.unchanged(selection)
        if (type == BlockType.CodeBlock) {
            val prose = lines.takeWhile { !fences.roleAt(it.start).isCode }
            return wrap(text, selection, style, sources(prose, raw = false), fences)
        }
        if (lines.any { fences.roleAt(it.start).isCode }) {
            // A selection running into a code block converts only the prose before it.
            val prose = lines.takeWhile { !fences.roleAt(it.start).isCode }
            return convert(text, selection, style, type, sources(prose, raw = false))
        }
        return convert(text, selection, style, type, sources(lines, raw = false))
    }

    /**
     * The ¶ menu inside a fenced block acts on the caret's line, or the
     * selected lines, not the whole block (docs/ux-spec.md, "Formatting
     * strip"): the block splits around them. Code lines above keep their
     * fence and get a closing one; code lines below get a new opening fence
     * like the block's own (same characters, length and info string); the
     * chosen lines come out of the fence as [type], taken literally. A
     * selection over every line of the block (or a fence) unwraps it whole.
     * No empty fenced block is ever left: a side with no lines loses its
     * fence, and one with only blank lines leaves them as blank lines.
     */
    private fun fromCode(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        type: BlockType,
        fences: FenceStates,
    ): EditResult {
        val block =
            CodeBlockToggle.enclosingBlock(text, TextLines.lineAt(text, selection.start), fences)
                ?: return EditResult.unchanged(selection)
        val inside = ArrayList<LineSpan>()
        var line = TextLines.next(text, block.open)
        while (line != null && fences.roleAt(line.start) == FenceRole.INSIDE) {
            inside += line
            line = TextLines.next(text, line)
        }
        if (inside.isEmpty()) return CodeBlockToggle.apply(text, selection, style, fences)
        val selected = TextLines.linesIn(text, selection)
        val onFence = selected.any { it.start == block.open.start || it.start == block.close?.start }
        val chosen =
            if (onFence) {
                inside
            } else {
                inside.filter {
                    it.start in selected.first().start..selected.last().start
                }
            }
        val above = inside.filter { it.start < chosen.first().start }
        val below = inside.filter { it.start > chosen.last().start }
        val opener = block.open.content
        val indent = opener.takeWhile { it == ' ' }
        val closer = indent + opener.substring(indent.length).takeWhile { it == opener[indent.length] }
        val fenced = EditBuilder(text)
        if (above.any { !it.isBlank }) {
            fenced.insert(chosen.first().start, "$closer\n")
        } else {
            fenced.delete(block.open.start, block.open.end + 1)
        }
        val close = block.close
        if (below.any { !it.isBlank }) {
            fenced.insert(below.first().start, "$opener\n")
        } else if (close != null) {
            fenced.delete(close.start - 1, close.end)
        }
        // The selection stays on the chosen lines, past a closing fence written where they start.
        val from = fenced.map(selection.start, stickAfter = true)
        val split =
            fenced.build(
                TextSelection(from, maxOf(from, fenced.map(selection.end, stickAfter = selection.isCaret))),
            )
        val middle = split.applyTo(text)
        val lines = ArrayList<LineSpan>()
        var at: LineSpan? = TextLines.lineAt(middle, fenced.map(chosen.first().start))
        while (at != null && lines.size < chosen.size) {
            lines += at
            at = TextLines.next(middle, at)
        }
        val converted = convert(middle, split.selection, style, type, sources(lines, raw = true))
        return oneEdit(text, converted.applyTo(middle), converted.selection)
    }

    /** The change from [text] to [after] as one minimal op (common start and end trimmed). */
    private fun oneEdit(
        text: CharSequence,
        after: String,
        selection: TextSelection,
    ): EditResult {
        var head = 0
        val limit = minOf(text.length, after.length)
        while (head < limit && text[head] == after[head]) head++
        var tail = 0
        while (tail < limit - head && text[text.length - 1 - tail] == after[after.length - 1 - tail]) tail++
        val builder = EditBuilder(text)
        builder.replace(head, text.length - tail, after.substring(head, after.length - tail))
        return builder.build(selection)
    }

    /** A line to convert: where its text starts, and whether it was a block of its own. */
    private class Source(
        val span: LineSpan,
        val prefix: LinePrefix,
        val bare: Int,
        val ownBlock: Boolean,
    )

    /** Code's lines ([raw]) are literal text: only their indentation goes, never a `- ` or `#`. */
    private fun sources(
        lines: List<LineSpan>,
        raw: Boolean,
    ): List<Source> =
        lines.map { line ->
            if (raw) {
                val indent = line.content.length - line.content.trimStart().length
                Source(line, LinePrefix.parse(""), indent, ownBlock = false)
            } else {
                val p = LinePrefix.parse(line.content)
                Source(line, p, bareStart(line.content, p), isOwnBlock(line.content, p))
            }
        }

    private fun convert(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        type: BlockType,
        lines: List<Source>,
    ): EditResult {
        if (lines.isEmpty()) return EditResult.unchanged(selection)
        val builder = EditBuilder(text)
        val numbering = Numbering(text, lines.first().span, style)
        var previous: Source? = null
        for (source in lines) {
            val line = source.span
            if (line.isBlank) {
                if (type == BlockType.Quote && lines.size > 1) builder.replace(line.start, line.end, ">")
            } else {
                val separate = type == BlockType.Paragraph && separates(text, source, previous)
                val prefix = prefixFor(type, source.prefix, style, numbering)
                builder.replace(line.start, line.start + source.bare, (if (separate) "\n" else "") + prefix)
            }
            previous = source
        }
        if (type !in HEADINGS) separateBelow(text, lines.last().span, builder)
        // An item taken out of a numbered list splits it: the items after it count on as a list of their own.
        val firstPrefix = lines.first().prefix
        val number = (firstPrefix.marker as? ListMarker.Ordered)?.number
        if (number != null && type != BlockType.Numbered) {
            Renumbering.splitRest(text, builder, lines.last().span, firstPrefix, number)
        }
        return builder.buildMapping(selection)
    }

    /**
     * Whether a paragraph made of [source] needs a blank line above it: the
     * first converted line under any text, and each line that was (or
     * follows) a block of its own, so items stay separate paragraphs.
     */
    private fun separates(
        text: CharSequence,
        source: Source,
        previous: Source?,
    ): Boolean =
        when {
            // Under any text, a bare line would join it; a fence line is no paragraph to join.
            previous == null -> {
                TextLines.previous(text, source.span)?.let { !it.isBlank && !isFence(it.content) } ==
                    true
            }

            previous.span.isBlank -> {
                false
            }

            else -> {
                previous.ownBlock || source.ownBlock
            }
        }

    /** Where the line's text starts once its quote, indent, list marker, task box and heading are off. */
    private fun bareStart(
        content: String,
        p: LinePrefix,
    ): Int {
        if (p.isListItem) return p.contentStart
        val (level, end) = HeadingEdit.parse(content.substring(p.quoteEnd).trimStart())
        val lead = content.length - content.substring(p.quoteEnd).trimStart().length
        return if (level > 0) lead + end else p.indentEnd
    }

    /** A list item or heading is a block of its own; a plain line may be one line of a paragraph. */
    private fun isOwnBlock(
        content: String,
        p: LinePrefix,
    ): Boolean = p.isListItem || HeadingEdit.parse(content.substring(p.quoteEnd).trimStart()).first > 0

    private fun prefixFor(
        type: BlockType,
        p: LinePrefix,
        style: DocumentStyle,
        numbering: Numbering,
    ): String {
        // List types keep nesting; everything else starts at the margin.
        val indent = if (p.isListItem) p.indent else ""
        return when (type) {
            BlockType.Paragraph -> {
                ""
            }

            BlockType.Heading1 -> {
                "# "
            }

            BlockType.Heading2 -> {
                "## "
            }

            BlockType.Heading3 -> {
                "### "
            }

            BlockType.Bullet -> {
                "$indent${(p.marker as? ListMarker.Bullet)?.char ?: style.bullet} "
            }

            BlockType.Numbered -> {
                "$indent${numbering.next(indent)}${numbering.delimiter} "
            }

            BlockType.Task -> {
                val box = if (p.isChecked) "[x]" else "[ ]"
                "$indent${(p.marker as? ListMarker.Bullet)?.char ?: style.bullet} $box "
            }

            BlockType.Quote -> {
                "> "
            }

            BlockType.CodeBlock -> {
                error("a code block is wrapped, not prefixed")
            }
        }
    }

    /** A plain line under the converted lines would join their block (lazy continuation): keep the two apart. */
    private fun separateBelow(
        text: CharSequence,
        last: LineSpan,
        builder: EditBuilder,
    ) {
        val next = TextLines.next(text, last) ?: return
        if (next.isBlank || last.isBlank) return
        val p = LinePrefix.parse(next.content)
        val interrupts = p.isListItem || p.quoteEnd > 0 || isOwnBlock(next.content, p) || isFence(next.content)
        // At the next line's start, not the converted line's end, so a caret at that end stays put.
        if (!interrupts) builder.insert(next.start, "\n")
    }

    private fun isFence(content: String): Boolean =
        content.trimStart().let {
            it.startsWith("```") ||
                it.startsWith("~~~")
        }

    /**
     * Strips each line's block syntax and puts the lines between fences their
     * content cannot close. Right under a fenced block, or right above one,
     * the lines join that block instead of starting a second one beside it.
     */
    @Suppress("CyclomaticComplexMethod") // Join above, join below, or new fences: three small cases side by side.
    private fun wrap(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        sources: List<Source>,
        fences: FenceStates,
    ): EditResult {
        if (sources.isEmpty()) return EditResult.unchanged(selection)
        val builder = EditBuilder(text)
        val lines = sources.map { it.span }
        val bodies = sources.map { if (it.span.isBlank) "" else it.span.content.substring(it.bare) }
        val first = lines.first()
        val last = lines.last()
        val above = TextLines.previous(text, first)?.takeIf { fences.roleAt(it.start) == FenceRole.CLOSING_FENCE }
        val below = TextLines.next(text, last)?.takeIf { fences.roleAt(it.start) == FenceRole.OPENING_FENCE }
        val fence = fenceFor(bodies, style.codeFence[0])
        val alone = above == null && below == null
        if (alone && lines.size == 1 && first.content.isEmpty()) {
            // An empty line: an empty block with the caret inside it.
            builder.insert(first.start, "$fence\n\n$fence")
            return builder.build(TextSelection.caret(first.start + fence.length + 1))
        }
        lines.forEachIndexed { i, line -> builder.replace(line.start, line.end, bodies[i]) }
        when {
            above != null -> builder.delete(above.start - 1, above.end)
            below != null -> builder.insert(first.start, below.content + "\n")
            else -> builder.insert(first.start, "$fence\n")
        }
        when {
            below != null -> builder.delete(last.end, below.end)
            above != null -> builder.insert(last.end, "\n" + above.content)
            else -> builder.insert(last.end, "\n$fence")
        }
        // The selection moves inside the fences: its start past the opener, its end before the closer.
        val atClose = selection.start == last.end && last.end != first.start
        val start = builder.map(selection.start, stickAfter = !atClose)
        val end = if (selection.isCaret) start else maxOf(start, builder.map(selection.end, stickAfter = false))
        return builder.build(TextSelection(start, end))
    }

    /** A fence longer than any run of its char in the content, so the content can never close it early. */
    private fun fenceFor(
        bodies: List<String>,
        c: Char,
    ): String {
        var longest = 0
        for (body in bodies) {
            var run = 0
            for (ch in body) {
                run = if (ch == c) run + 1 else 0
                longest = maxOf(longest, run)
            }
        }
        return c.toString().repeat(maxOf(MIN_FENCE, longest + 1))
    }

    /** Numbers for lines becoming a numbered list: a list right above at the same indent is continued. */
    private class Numbering(
        text: CharSequence,
        first: LineSpan,
        style: DocumentStyle,
    ) {
        private val counters = HashMap<String, Long>()
        val delimiter: Char

        init {
            val above = TextLines.previous(text, first)?.let { LinePrefix.parse(it.content) }
            val marker = above?.marker
            // Converted lines come out at the margin: only an unquoted list above is theirs to continue.
            if (marker is ListMarker.Ordered && above.quoteEnd == 0) {
                counters[above.indent] = marker.number + 1
                delimiter = marker.delimiter
            } else {
                delimiter = style.orderedDelimiter
            }
        }

        fun next(indent: String): Long {
            val n = counters[indent] ?: 1L
            counters[indent] = n + 1
            return n
        }
    }
}
