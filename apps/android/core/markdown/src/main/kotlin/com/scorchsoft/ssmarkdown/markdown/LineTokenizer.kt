package com.scorchsoft.ssmarkdown.markdown

import com.scorchsoft.ssmarkdown.markdown.LineState.BlockMode

/**
 * Tokenises one line of Markdown for the Code pane, given the state the
 * previous line ended in: `tokenize(line, start) -> (tokens, endState)`.
 *
 * Per-line on purpose. The editor only ever needs the lines it draws, and a
 * per-line function with a small carried [LineState] is what lets the
 * [IncrementalTokenizer] stop re-tokenising as soon as an edit's effect dies
 * out, the approach of VS Code's TextMate tokenizer and CodeMirror
 * (docs/performance-techniques.md). The block rules follow CommonMark 0.31
 * with GFM tables, strikethrough, task lists and autolinks, plus front
 * matter, `$` math and footnotes; the simplifications are listed on
 * [LineState] and [InlineScanner].
 *
 * Work per line is bounded: only the first [MAX_TOKENIZED_COLUMNS]
 * characters are read, and the rest of a longer line is plain text
 * (docs/robustness.md, "Long lines are chunked").
 *
 * Not thread-safe: an instance reuses scratch buffers between calls. Make
 * one per thread; they are cheap.
 */
@Suppress("TooManyFunctions") // One small function per block rule; together they are the block grammar.
public class LineTokenizer {
    private val out = TokenBuilder()
    private val inline = InlineScanner(out)
    private val marker = BlockSyntax.ListMarker()
    private val definition = BlockSyntax.Definition()

    private var text: CharSequence = ""
    private var end = 0
    private var pos = 0
    private var col = 0

    private var kind = LineKind.PARAGRAPH
    private var level = 0
    private var previousRole = LineRole.AS_IS
    private var label: String? = null
    private var endBits = 0L
    private var endContainers = 0L
    private var endInfo: String? = null
    private var depth = 0

    private var leafStart = 0
    private var leafLevel = 0
    private var leafLength = 0

    /**
     * Tokenises [line] (without its line break) starting in [start]. [role]
     * is how the line below asks this one to be read; the
     * [IncrementalTokenizer] passes it, and a role never changes the end state.
     */
    public fun tokenize(
        line: CharSequence,
        start: LineState,
        role: LineRole = LineRole.AS_IS,
    ): LineResult {
        text = line
        end = minOf(line.length, MAX_TOKENIZED_COLUMNS)
        out.reset(end)
        pos = 0
        col = 0
        kind = LineKind.PARAGRAPH
        level = 0
        previousRole = LineRole.AS_IS
        label = null
        endBits = 0L
        endContainers = 0L
        endInfo = null
        when {
            start.isDocumentStart && opensFrontMatter() -> Unit
            start.mode == BlockMode.FRONT_MATTER -> frontMatterLine(start)
            else -> blockLine(start, role)
        }
        val endState = LineState.of(endBits, endContainers, endInfo, start)
        return LineResult(out.build(), endState, kind, level, previousRole, label)
    }

    private fun opensFrontMatter(): Boolean {
        val toml = isDelimiterLine('+')
        if (!toml && !isDelimiterLine('-')) return false
        out.paint(0, end, TokenKind.FRONT_MATTER_DELIMITER)
        kind = LineKind.FRONT_MATTER
        endBits = BlockMode.FRONT_MATTER.ordinal.toLong() or (if (toml) LineState.TOML else 0L)
        return true
    }

    private fun frontMatterLine(start: LineState) {
        kind = LineKind.FRONT_MATTER
        val closes = if (start.isTomlFrontMatter) isDelimiterLine('+') else isDelimiterLine('-') || isDelimiterLine('.')
        if (closes) {
            out.paint(0, end, TokenKind.FRONT_MATTER_DELIMITER)
        } else {
            out.paint(0, end, TokenKind.FRONT_MATTER_TEXT)
            endBits = start.bits
        }
    }

    /** Exactly three [c] then only spaces: `---`, `+++`, `...`. */
    private fun isDelimiterLine(c: Char): Boolean =
        end >= FRONT_MATTER_FENCE && (0 until FRONT_MATTER_FENCE).all { text[it] == c } &&
            BlockSyntax.isBlank(text, FRONT_MATTER_FENCE, end)

    private fun blockLine(
        start: LineState,
        role: LineRole,
    ) {
        val startDepth = start.containerDepth
        val matched = matchContainers(start, startDepth)
        val allMatched = matched == startDepth
        if (allMatched && start.mode != BlockMode.NORMAL) {
            endContainers = start.containers
            continueLeaf(start)
            return
        }
        val paragraphOpen = start.mode == BlockMode.NORMAL && start.isParagraphOpen
        endContainers = LineState.truncate(start.containers, matched)
        depth = matched
        val opened = openContainers(paragraphOpen)
        val continuesParagraph = paragraphOpen && opened == 0
        val sameContainers = allMatched && opened == 0
        val leaf =
            classify(
                paragraphOpen = continuesParagraph,
                setext = continuesParagraph && allMatched,
                table = sameContainers && start.mode == BlockMode.NORMAL && start.isInTable,
                headerCells = if (sameContainers) start.pipeCells else 0,
            )
        // A lazy continuation line keeps the quotes and list items it failed to match.
        if (continuesParagraph && !allMatched && leaf == LEAF_PARAGRAPH) endContainers = start.containers
        emitLeaf(leaf, role)
    }

    private fun matchContainers(
        start: LineState,
        startDepth: Int,
    ): Int {
        var matched = 0
        while (matched < startDepth && continuesContainer(start, matched)) matched++
        return matched
    }

    /** Consumes container [level]'s marker or indent if this line continues it. A blank line continues a list item. */
    private fun continuesContainer(
        start: LineState,
        level: Int,
    ): Boolean {
        val p = BlockSyntax.skipSpaces(text, pos, end)
        val continues =
            if (start.isQuote(level)) {
                p < end && text[p] == '>' && columnAt(p) - col <= BlockSyntax.MAX_INDENT
            } else {
                p >= end || columnAt(p) >= start.listContentColumn(level)
            }
        when {
            !continues -> Unit
            start.isQuote(level) -> quoteMarker(p)
            p < end -> while (col < start.listContentColumn(level)) advance()
        }
        return continues
    }

    private fun openContainers(paragraphOpen: Boolean): Int {
        var opened = 0
        while (depth < LineState.MAX_CONTAINER_DEPTH && openContainer(paragraphOpen && opened == 0)) opened++
        return opened
    }

    /** Opens one quote or list item at the current position, if one starts there. */
    private fun openContainer(interruptsParagraph: Boolean): Boolean {
        val p = BlockSyntax.skipSpaces(text, pos, end)
        return when {
            p >= end || columnAt(p) - col > BlockSyntax.MAX_INDENT -> {
                false
            }

            text[p] == '>' -> {
                quoteMarker(p)
                endContainers = LineState.push(endContainers, depth++, LineState.QUOTE)
                true
            }

            else -> {
                openListItem(p, interruptsParagraph)
            }
        }
    }

    private fun openListItem(
        p: Int,
        interruptsParagraph: Boolean,
    ): Boolean {
        val width = if (BlockSyntax.isThematicBreak(text, p, end)) 0 else BlockSyntax.listMarker(text, p, end, marker)
        // An empty item, or a list not starting at 1, cannot interrupt a paragraph.
        val blocked = interruptsParagraph && (marker.empty || (marker.ordered && !marker.startsAtOne))
        val opens = width > 0 && !blocked
        if (opens) listItem(p, width)
        return opens
    }

    private fun quoteMarker(p: Int) {
        moveTo(p)
        out.paint(p, p + 1, TokenKind.QUOTE_MARKER)
        advance()
        if (pos < end && BlockSyntax.isSpace(text[pos])) advance()
    }

    private fun listItem(
        p: Int,
        width: Int,
    ) {
        moveTo(p)
        out.paint(p, p + width, TokenKind.LIST_MARKER)
        repeat(width) { advance() }
        val markerEnd = col
        val contentAt = BlockSyntax.skipSpaces(text, pos, end)
        val spaces = columnAt(contentAt) - col
        val content =
            if (marker.empty || spaces > MAX_LIST_GAP) {
                if (!marker.empty) advance()
                markerEnd + 1
            } else {
                moveTo(contentAt)
                col
            }
        endContainers = LineState.push(endContainers, depth++, minOf(content, LineState.MAX_LIST_COLUMN))
        taskBox()
    }

    private fun taskBox() {
        val box = pos + 2
        val isBox = box < end && text[pos] == '[' && text[box] == ']' && text[pos + 1] in TASK_MARKS
        if (!isBox || (box + 1 < end && !BlockSyntax.isSpace(text[box + 1]))) return
        out.paint(pos, box + 1, if (text[pos + 1] == ' ') TokenKind.TASK_OPEN else TokenKind.TASK_DONE)
        repeat(TASK_BOX_WIDTH) { advance() }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // A precedence list: the first rule that matches wins.
    private fun classify(
        paragraphOpen: Boolean,
        setext: Boolean,
        table: Boolean,
        headerCells: Int,
    ): Int {
        val p = BlockSyntax.skipSpaces(text, pos, end)
        leafStart = p
        if (p >= end) return LEAF_BLANK
        if (columnAt(p) - col > BlockSyntax.MAX_INDENT) {
            return when {
                paragraphOpen -> LEAF_PARAGRAPH
                table -> LEAF_TABLE_ROW
                else -> LEAF_INDENTED_CODE
            }
        }
        if (setext) {
            leafLevel = BlockSyntax.setextLevel(text, p, end)
            if (leafLevel > 0) return LEAF_SETEXT
            if (headerCells > 0 &&
                BlockSyntax.delimiterRowCells(text, p, end) == headerCells
            ) {
                return LEAF_TABLE_DELIMITER
            }
        }
        leafLevel = BlockSyntax.atxLevel(text, p, end)
        if (leafLevel > 0) return LEAF_ATX
        leafLength = BlockSyntax.fenceLength(text, p, end)
        if (leafLength > 0) return LEAF_FENCE
        if (BlockSyntax.regionMatches(text, p, MATH_FENCE)) return LEAF_MATH
        if (BlockSyntax.isThematicBreak(text, p, end)) return LEAF_RULE
        leafLevel = HtmlBlocks.startType(text, p, end)
        if (leafLevel > 0 && (leafLevel != HtmlBlocks.TYPE_ANY_TAG || !paragraphOpen)) return LEAF_HTML
        if (!paragraphOpen && BlockSyntax.referenceDefinition(text, p, end, definition)) {
            return if (definition.isFootnote) LEAF_FOOTNOTE else LEAF_REFERENCE
        }
        return if (table) LEAF_TABLE_ROW else LEAF_PARAGRAPH
    }

    @Suppress("CyclomaticComplexMethod") // One branch per leaf kind, each a single call.
    private fun emitLeaf(
        leaf: Int,
        role: LineRole,
    ) {
        when (leaf) {
            LEAF_BLANK -> {
                kind = LineKind.BLANK
            }

            LEAF_INDENTED_CODE -> {
                paintLine(TokenKind.CODE_BLOCK_TEXT, LineKind.CODE)
            }

            LEAF_SETEXT -> {
                out.paint(leafStart, end, TokenKind.SETEXT_UNDERLINE)
                kind = LineKind.SETEXT_UNDERLINE
                previousRole = if (leafLevel == 1) LineRole.SETEXT_HEADING_1 else LineRole.SETEXT_HEADING_2
            }

            LEAF_TABLE_DELIMITER -> {
                out.paint(leafStart, end, TokenKind.TABLE_DELIMITER_ROW)
                paintPipes(leafStart)
                kind = LineKind.TABLE_DELIMITER
                previousRole = LineRole.TABLE_HEADER
                endBits = LineState.TABLE
            }

            LEAF_ATX -> {
                heading()
            }

            LEAF_FENCE -> {
                openFence()
            }

            LEAF_MATH -> {
                openMath()
            }

            LEAF_RULE -> {
                paintLine(TokenKind.HORIZONTAL_RULE, LineKind.HORIZONTAL_RULE)
            }

            LEAF_HTML -> {
                openHtml()
            }

            LEAF_REFERENCE -> {
                referenceDefinition()
            }

            LEAF_FOOTNOTE -> {
                footnoteDefinition()
            }

            LEAF_TABLE_ROW -> {
                inline.scan(text, pos, end, TokenKind.PLAIN)
                paintInlinePipes()
                kind = LineKind.TABLE_ROW
                endBits = LineState.TABLE
            }

            else -> {
                paragraph(role)
            }
        }
    }

    private fun paintLine(
        token: TokenKind,
        lineKind: LineKind,
    ) {
        out.paint(pos, end, token)
        kind = lineKind
    }

    private fun heading() {
        val markerEnd = leafStart + leafLevel
        val contentStart = BlockSyntax.skipSpaces(text, markerEnd, end)
        val contentEnd = BlockSyntax.trimEnd(text, contentStart, end)
        val closing = BlockSyntax.atxClosingStart(text, contentStart, contentEnd)
        out.paint(leafStart, markerEnd, TokenKind.HEADING_MARKER)
        out.paint(markerEnd, end, TokenKind.HEADING_TEXT)
        inline.scan(text, contentStart, BlockSyntax.trimEnd(text, contentStart, closing), TokenKind.HEADING_TEXT)
        out.paint(closing, contentEnd, TokenKind.HEADING_MARKER)
        kind = LineKind.HEADING
        level = leafLevel
    }

    private fun openFence() {
        val fenceEnd = leafStart + leafLength
        val infoStart = BlockSyntax.skipSpaces(text, fenceEnd, end)
        val infoEnd = BlockSyntax.trimEnd(text, infoStart, end)
        out.paint(leafStart, fenceEnd, TokenKind.CODE_FENCE)
        out.paint(infoStart, infoEnd, TokenKind.CODE_FENCE_INFO)
        kind = LineKind.CODE_FENCE
        endInfo =
            if (infoEnd >
                infoStart
            ) {
                text.subSequence(infoStart, minOf(infoEnd, infoStart + MAX_INFO)).toString()
            } else {
                null
            }
        endBits = BlockMode.FENCED_CODE.ordinal.toLong() or
            (minOf(leafLength, MAX_FENCE).toLong() shl LineState.FENCE_LENGTH_SHIFT) or
            (if (text[leafStart] == '~') LineState.FENCE_TILDE else 0L)
    }

    private fun openMath() {
        val contentStart = leafStart + MATH_FENCE.length
        out.paint(leafStart, contentStart, TokenKind.MATH_DELIMITER)
        kind = LineKind.MATH
        if (!closeMath(contentStart)) endBits = BlockMode.MATH.ordinal.toLong()
    }

    /** Paints math from [from] to the line's end, closing on a trailing `$$`; true if it closed. */
    private fun closeMath(from: Int): Boolean {
        val trimmed = BlockSyntax.trimEnd(text, from, end)
        val closes =
            trimmed - MATH_FENCE.length >= from &&
                BlockSyntax.regionMatches(text, trimmed - MATH_FENCE.length, MATH_FENCE)
        out.paint(from, end, TokenKind.MATH_TEXT)
        if (closes) out.paint(trimmed - MATH_FENCE.length, trimmed, TokenKind.MATH_DELIMITER)
        return closes
    }

    private fun openHtml() {
        val type = leafLevel
        out.paint(pos, end, TokenKind.HTML_BLOCK)
        kind = LineKind.HTML
        val closed = HtmlBlocks.endsOnLine(type, text, HtmlBlocks.endSearchStart(type, leafStart), end)
        if (!closed) endBits = BlockMode.HTML.ordinal.toLong() or (type.toLong() shl LineState.HTML_TYPE_SHIFT)
    }

    private fun referenceDefinition() {
        val d = definition
        out.paint(d.labelStart - 1, d.destStart, TokenKind.LINK_PUNCTUATION)
        out.paint(d.labelStart, d.labelEnd, TokenKind.LINK_LABEL)
        out.paint(d.destStart, d.destEnd, TokenKind.LINK_URL)
        if (d.titleStart >= 0) out.paint(d.titleStart, d.titleEnd, TokenKind.LINK_TITLE)
        kind = LineKind.REFERENCE_DEFINITION
        label = BlockSyntax.normalizeLabel(text, d.labelStart, d.labelEnd)
    }

    private fun footnoteDefinition() {
        val d = definition
        val bodyStart = d.labelEnd + 2
        inline.scan(text, bodyStart, end, TokenKind.PLAIN)
        out.paint(d.labelStart - 1, bodyStart, TokenKind.FOOTNOTE_LABEL)
        kind = LineKind.FOOTNOTE_DEFINITION
        label = "^" + BlockSyntax.normalizeLabel(text, d.labelStart + 1, d.labelEnd)
    }

    private fun paragraph(role: LineRole) {
        val setext = role == LineRole.SETEXT_HEADING_1 || role == LineRole.SETEXT_HEADING_2
        inline.scan(text, pos, end, if (setext) TokenKind.HEADING_TEXT else TokenKind.PLAIN)
        when {
            setext -> {
                kind = LineKind.HEADING
                level = if (role == LineRole.SETEXT_HEADING_1) 1 else 2
            }

            role == LineRole.TABLE_HEADER -> {
                paintInlinePipes()
                kind = LineKind.TABLE_HEADER
            }

            else -> {
                kind = LineKind.PARAGRAPH
            }
        }
        endBits = LineState.PARAGRAPH or (pipeCells().toLong() shl LineState.PIPE_CELLS_SHIFT)
    }

    /** Table cells this paragraph line would have as a header row; 0 without a pipe. */
    private fun pipeCells(): Int {
        val count = inline.pipeCount
        if (count == 0) return 0
        val leading = if (inline.pipes[0] == leafStart) 1 else 0
        val trailing = if (inline.pipes[count - 1] == BlockSyntax.trimEnd(text, pos, end) - 1) 1 else 0
        return (count + 1 - leading - trailing).coerceIn(1, LineState.PIPE_CELLS_MASK.toInt())
    }

    private fun paintInlinePipes() {
        for (i in 0 until inline.pipeCount) out.paint(inline.pipes[i], inline.pipes[i] + 1, TokenKind.TABLE_PIPE)
    }

    private fun paintPipes(from: Int) {
        for (i in from until end) if (text[i] == '|') out.paint(i, i + 1, TokenKind.TABLE_PIPE)
    }

    private fun continueLeaf(start: LineState) {
        when (start.mode) {
            BlockMode.FENCED_CODE -> {
                continueFence(start)
            }

            BlockMode.MATH -> {
                kind = LineKind.MATH
                if (!closeMath(pos)) endBits = start.bits
            }

            else -> {
                continueHtml(start)
            }
        }
    }

    private fun continueFence(start: LineState) {
        val p = BlockSyntax.skipSpaces(text, pos, end)
        val indent = columnAt(p) - col
        if (indent <= BlockSyntax.MAX_INDENT && p < end &&
            BlockSyntax.closesFence(text, p, end, start.fenceChar, start.fenceLength)
        ) {
            out.paint(p, BlockSyntax.trimEnd(text, p, end), TokenKind.CODE_FENCE)
            kind = LineKind.CODE_FENCE
        } else {
            out.paint(pos, end, TokenKind.CODE_BLOCK_TEXT)
            kind = LineKind.CODE
            endBits = start.bits
            endInfo = start.fenceInfo
        }
    }

    private fun continueHtml(start: LineState) {
        val type = start.htmlBlockType
        if (type >= HtmlBlocks.TYPE_BLOCK_TAG && BlockSyntax.isBlank(text, pos, end)) {
            kind = LineKind.BLANK
            return
        }
        out.paint(pos, end, TokenKind.HTML_BLOCK)
        kind = LineKind.HTML
        if (type >= HtmlBlocks.TYPE_BLOCK_TAG || !HtmlBlocks.endsOnLine(type, text, pos, end)) endBits = start.bits
    }

    /** The column [p] is at, from the current position; tabs stop every four columns. */
    private fun columnAt(p: Int): Int {
        var c = col
        for (i in pos until p) c = if (text[i] == '\t') (c / TAB_STOP + 1) * TAB_STOP else c + 1
        return c
    }

    private fun moveTo(p: Int) {
        col = columnAt(p)
        pos = p
    }

    private fun advance() {
        col = if (text[pos] == '\t') (col / TAB_STOP + 1) * TAB_STOP else col + 1
        pos++
    }

    /** Limits shared with the editor. */
    public companion object {
        /**
         * Only this many characters of a line are tokenised; the rest is plain.
         * One pathological line (a 1 MB base64 image) must cost no more than
         * a screenful of ordinary ones.
         */
        public const val MAX_TOKENIZED_COLUMNS: Int = 10_000

        private const val FRONT_MATTER_FENCE = 3
        private const val MAX_LIST_GAP = 4
        private const val TASK_BOX_WIDTH = 3
        private const val TASK_MARKS = " xX"
        private const val TAB_STOP = 4
        private const val MAX_FENCE = 255
        private const val MAX_INFO = 128
        private const val MATH_FENCE = "$$"

        private const val LEAF_BLANK = 0
        private const val LEAF_INDENTED_CODE = 1
        private const val LEAF_SETEXT = 2
        private const val LEAF_TABLE_DELIMITER = 3
        private const val LEAF_ATX = 4
        private const val LEAF_FENCE = 5
        private const val LEAF_MATH = 6
        private const val LEAF_RULE = 7
        private const val LEAF_HTML = 8
        private const val LEAF_REFERENCE = 9
        private const val LEAF_FOOTNOTE = 10
        private const val LEAF_TABLE_ROW = 11
        private const val LEAF_PARAGRAPH = 12
    }
}
