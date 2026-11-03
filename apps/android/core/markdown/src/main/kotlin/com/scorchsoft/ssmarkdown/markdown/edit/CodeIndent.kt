package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * Indentation inside a fenced code block, for editing code (docs/ux-spec.md,
 * "Formatting strip"). Code is text, so nothing here ever touches list
 * nesting or block syntax:
 *
 * - Indent adds one unit at the start of the caret's line, or of every
 *   non-blank selected line; Outdent removes up to one unit.
 * - The unit is the block's own: a tab if its lines are tab-indented,
 *   otherwise the commonest step between its lines' indents (2 or 4
 *   spaces), and 4 spaces when it has none yet.
 * - Enter keeps the line's indentation, plus one unit after a line ending
 *   in `{`, `[` or `(` (or `:` in a Python or YAML block).
 * - `}`, `]` or `)` typed as the first character of a line takes one unit
 *   back off it.
 */
internal object CodeIndent {
    private const val DEFAULT_SPACES = 4
    private const val NARROW = 2

    /** How many lines of the block are read to find its unit. */
    private const val MAX_SAMPLE_LINES = 2_000
    private val OPENERS = setOf('{', '[', '(')
    private val CLOSERS = setOf('}', ']', ')')
    private val COLON_LANGUAGES = setOf("python", "py", "yaml", "yml")

    /** True when [line] is a line of fenced code, between its fences. */
    fun isCode(
        fences: FenceStates,
        line: LineSpan,
    ): Boolean = fences.roleAt(line.start) == FenceRole.INSIDE

    /** Indent or outdent the code lines of [selection], or null when its first line is not code. */
    fun shift(
        text: CharSequence,
        selection: TextSelection,
        fences: FenceStates,
        outdent: Boolean,
    ): EditResult? {
        val first = TextLines.lineAt(text, selection.start)
        if (!isCode(fences, first)) return null
        val unit = unitAt(text, first, fences)
        val all = TextLines.linesIn(text, selection).filter { isCode(fences, it) }
        val lines = if (selection.isCaret) all else all.filterNot { it.isBlank }
        val builder = EditBuilder(text)
        for (line in lines) {
            if (!outdent) {
                builder.insert(line.start, unit)
            } else {
                val remove =
                    when {
                        line.content.startsWith('\t') -> {
                            1
                        }

                        else -> {
                            line.content.takeWhile { it == ' ' }.length.coerceAtMost(
                                if (unit ==
                                    "\t"
                                ) {
                                    DEFAULT_SPACES
                                } else {
                                    unit.length
                                },
                            )
                        }
                    }
                if (remove > 0) builder.delete(line.start, line.start + remove)
            }
        }
        return builder.buildMapping(selection)
    }

    /** What Enter on [line] continues with: its indentation, one unit more after an opener. */
    fun newlineIndent(
        text: CharSequence,
        line: LineSpan,
        column: Int,
        fences: FenceStates,
    ): String {
        val indent = line.content.takeWhile { it == ' ' || it == '\t' }
        if (column < indent.length) return line.content.substring(0, column)
        val before = line.content.substring(0, column).trimEnd()
        val last = before.lastOrNull() ?: return indent
        val opens = last in OPENERS || (last == ':' && language(text, line, fences) in COLON_LANGUAGES)
        return if (opens) indent + unitAt(text, line, fences) else indent
    }

    /**
     * [typed] when it is a closer typed as the first character of a code
     * line: one unit comes off the line's indentation first. Null otherwise.
     */
    fun closer(
        text: CharSequence,
        selection: TextSelection,
        typed: String,
        fences: FenceStates,
    ): EditResult? {
        val c = typed.singleOrNull()
        if (c == null || c !in CLOSERS || !selection.isCaret) return null
        val line = TextLines.lineAt(text, selection.start)
        val before = line.content.substring(0, selection.start - line.start)
        if (!isCode(fences, line) || before.isEmpty() || before.isNotBlank()) return null
        val unit = unitAt(text, line, fences)
        val remove =
            when {
                before.endsWith('\t') -> {
                    1
                }

                else -> {
                    before.takeLastWhile { it == ' ' }.length.coerceAtMost(
                        if (unit ==
                            "\t"
                        ) {
                            DEFAULT_SPACES
                        } else {
                            unit.length
                        },
                    )
                }
            }
        val builder = EditBuilder(text)
        builder.replace(selection.start - remove, selection.start, typed)
        return builder.build(TextSelection.caret(selection.start - remove + 1))
    }

    /** The block's indent unit: see the class comment. */
    fun unitAt(
        text: CharSequence,
        line: LineSpan,
        fences: FenceStates,
    ): String {
        var first: LineSpan = line
        var sampled = 0
        var above = TextLines.previous(text, first)
        while (sampled < MAX_SAMPLE_LINES && above != null && isCode(fences, above)) {
            first = above
            above = TextLines.previous(text, first)
            sampled++
        }
        var twos = 0
        var fours = 0
        var previous = 0
        var current: LineSpan? = first
        var read = 0
        while (current != null && isCode(fences, current) && read++ < 2 * MAX_SAMPLE_LINES) {
            val content = current.content
            if (content.startsWith('\t')) return "\t"
            if (content.isNotBlank()) {
                val spaces = content.takeWhile { it == ' ' }.length
                when (kotlin.math.abs(spaces - previous)) {
                    NARROW -> twos++
                    DEFAULT_SPACES -> fours++
                }
                previous = spaces
            }
            current = TextLines.next(text, current)
        }
        return " ".repeat(if (twos > fours) NARROW else DEFAULT_SPACES)
    }

    /** The first word of the info string of the fence [line] is in, lower-cased; empty when there is none. */
    private fun language(
        text: CharSequence,
        line: LineSpan,
        fences: FenceStates,
    ): String {
        val open = CodeBlockToggle.enclosingBlock(text, line, fences)?.open ?: return ""
        return open.content
            .trimStart()
            .trimStart('`', '~')
            .trim()
            .substringBefore(' ')
            .lowercase()
    }
}
