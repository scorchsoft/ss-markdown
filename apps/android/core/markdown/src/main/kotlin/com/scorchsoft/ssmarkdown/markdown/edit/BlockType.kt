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
internal object BlockTypeEdit {
    private const val MIN_FENCE = 3
    private val HEADINGS = setOf(BlockType.Heading1, BlockType.Heading2, BlockType.Heading3)

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
        if (lines.any { fences.roleAt(it.start).isCode }) {
            // A selection running into a code block converts only the prose before it.
            val prose = lines.takeWhile { !fences.roleAt(it.start).isCode }
            return convert(text, selection, style, type, sources(prose, raw = false))
        }
        return convert(text, selection, style, type, sources(lines, raw = false))
    }

    /**
     * Unwraps the fenced block the selection starts in and converts all of
     * its lines (a caret converts the whole block), as one edit of the
     * block's span: everything outside it is untouched.
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
        val unwrapped = CodeBlockToggle.apply(text, TextSelection.caret(selection.start), style, fences)
        val middle = unwrapped.applyTo(text)
        var count = 0
        var inside = TextLines.next(text, block.open)
        while (inside != null && fences.roleAt(inside.start) == FenceRole.INSIDE) {
            count++
            inside = TextLines.next(text, inside)
        }
        if (count == 0) return unwrapped
        // The block's lines start where its opening fence was.
        val lines = ArrayList<LineSpan>()
        var line: LineSpan? = TextLines.lineAt(middle, minOf(block.open.start, middle.length))
        while (line != null && lines.size < count) {
            lines += line
            line = TextLines.next(middle, line)
        }
        val converted = convert(middle, unwrapped.selection, style, type, sources(lines, raw = true))
        val after = converted.applyTo(middle)
        val start = minOf(block.open.start, unwrapped.ops.minOf { it.offset })
        val end = block.close?.end ?: text.length
        val builder = EditBuilder(text)
        builder.replace(start, end, after.substring(start, end + after.length - text.length))
        return builder.build(converted.selection)
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
        if (type == BlockType.CodeBlock) return wrap(selection, style, lines, builder)
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
            // Under any text, a bare line would join it.
            previous == null -> TextLines.previous(text, source.span)?.isBlank == false

            previous.span.isBlank -> false

            else -> previous.ownBlock || source.ownBlock
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
        val fence = next.content.trimStart().let { it.startsWith("```") || it.startsWith("~~~") }
        val interrupts = p.isListItem || p.quoteEnd > 0 || isOwnBlock(next.content, p) || fence
        if (!interrupts) builder.insert(last.end, "\n")
    }

    /** Strips each line's block syntax and puts the lines between fences their content cannot close. */
    private fun wrap(
        selection: TextSelection,
        style: DocumentStyle,
        sources: List<Source>,
        builder: EditBuilder,
    ): EditResult {
        val lines = sources.map { it.span }
        val bodies = sources.map { if (it.span.isBlank) "" else it.span.content.substring(it.bare) }
        val c = style.codeFence[0]
        var longest = 0
        for (body in bodies) {
            var run = 0
            for (ch in body) {
                run = if (ch == c) run + 1 else 0
                longest = maxOf(longest, run)
            }
        }
        val fence = c.toString().repeat(maxOf(MIN_FENCE, longest + 1))
        val first = lines.first()
        val last = lines.last()
        if (lines.size == 1 && first.content.isEmpty()) {
            // An empty line: an empty block with the caret inside it.
            builder.insert(first.start, "$fence\n\n$fence")
            return builder.build(TextSelection.caret(first.start + fence.length + 1))
        }
        lines.forEachIndexed { i, line -> builder.replace(line.start, line.end, bodies[i]) }
        builder.insert(first.start, "$fence\n")
        builder.insert(last.end, "\n$fence")
        // The selection moves inside the fences: its start past the opener, its end before the closer.
        val atClose = selection.start == last.end && last.end != first.start
        val start = builder.map(selection.start, stickAfter = !atClose)
        val end = if (selection.isCaret) start else maxOf(start, builder.map(selection.end, stickAfter = false))
        return builder.build(TextSelection(start, end))
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
            if (marker is ListMarker.Ordered) {
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
