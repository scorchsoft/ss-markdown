package com.scorchsoft.ssmarkdown.markdown

/**
 * Everything the tokenizer must remember from one line to the next: the
 * end-of-line state of docs/robustness.md ("Editor view").
 *
 * Markdown is *almost* line-local. The exceptions are blocks that span
 * lines (a fence, front matter, a `$$` math block, an HTML block, a table)
 * and the container stack of quotes and list items that decides whether
 * an indented line is code or a continuation. This class is exactly that,
 * and nothing more, because its [equals] is what makes re-tokenising stop:
 * after an edit, the [IncrementalTokenizer] walks forward only until a
 * line ends in the same state it ended in before (VS Code's
 * `textModelTokens`). The smaller the state, the sooner that happens.
 *
 * It is packed into two longs and an optional info string so that equality
 * is two compares and a reference check, and so a paragraph line can hand
 * its start state straight on without allocating.
 *
 * Simplifications, all deliberate: at most [MAX_CONTAINER_DEPTH] nested
 * containers are tracked (deeper markers read as text); fence lengths
 * saturate at 255; list items track their content column but not whether
 * they are loose or tight.
 */
public class LineState internal constructor(
    internal val bits: Long,
    internal val containers: Long,
    /** The info string of the open fence (` ```kotlin title="x" ` gives `kotlin title="x"`), or null. */
    public val fenceInfo: String?,
) {
    /** The multi-line block the line ended inside, if any. */
    public val mode: BlockMode get() = BlockMode.ALL[(bits and MODE_MASK).toInt()]

    /** The open fence's character, `` ` `` or `~`; or `0` outside a fence. */
    public val fenceChar: Char
        get() =
            when {
                mode != BlockMode.FENCED_CODE -> 0.toChar()
                bits and FENCE_TILDE != 0L -> '~'
                else -> '`'
            }

    /** How many fence characters opened the block; a closing fence needs at least as many. */
    public val fenceLength: Int get() = ((bits ushr FENCE_LENGTH_SHIFT) and BYTE).toInt()

    /** The first word of [fenceInfo], which is what code highlighting keys on; null if there is none. */
    public val fenceLanguage: String?
        get() = fenceInfo?.substringBefore(' ')?.takeIf { it.isNotEmpty() }

    /** The CommonMark HTML block type, 1 to 7, while [mode] is [BlockMode.HTML]; otherwise 0. */
    public val htmlBlockType: Int get() = ((bits ushr HTML_TYPE_SHIFT) and HTML_TYPE_MASK).toInt()

    /**
     * True when the line ended inside a paragraph. The next line may then be
     * a lazy continuation, a setext underline or a table's delimiter row.
     */
    public val isParagraphOpen: Boolean get() = bits and PARAGRAPH != 0L

    /** True while the line is part of a table, after its delimiter row. */
    public val isInTable: Boolean get() = bits and TABLE != 0L

    /** Number of open containers (block quotes and list items), outermost first. */
    public val containerDepth: Int
        get() {
            var depth = 0
            while (depth < MAX_CONTAINER_DEPTH && containerAt(containers, depth) != 0) depth++
            return depth
        }

    /** Number of open block quotes, at any nesting. */
    public val quoteDepth: Int get() = (0 until containerDepth).count { isQuote(it) }

    /** True if container [level] (0 = outermost) is a block quote rather than a list item. */
    public fun isQuote(level: Int): Boolean = containerAt(containers, level) == QUOTE

    /** The column where list item [level]'s content starts; 0 if that container is a quote. */
    public fun listContentColumn(level: Int): Int = containerAt(containers, level).let { if (it == QUOTE) 0 else it }

    internal val isDocumentStart: Boolean get() = bits and DOCUMENT_START_BIT != 0L
    internal val pipeCells: Int get() = ((bits ushr PIPE_CELLS_SHIFT) and PIPE_CELLS_MASK).toInt()
    internal val isTomlFrontMatter: Boolean get() = bits and TOML != 0L

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is LineState && bits == other.bits && containers == other.containers && fenceInfo == other.fenceInfo)

    override fun hashCode(): Int =
        (bits * HASH_PRIME + containers).hashCode() * HASH_PRIME.toInt() + fenceInfo.hashCode()

    override fun toString(): String =
        buildString {
            append("LineState(").append(mode)
            if (mode == BlockMode.FENCED_CODE) append(" fence=").append(fenceChar).append('x').append(fenceLength)
            if (fenceInfo != null) append(" info=").append(fenceInfo)
            if (htmlBlockType != 0) append(" html=").append(htmlBlockType)
            if (isParagraphOpen) append(" paragraph")
            if (pipeCells != 0) append(" cells=").append(pipeCells)
            if (isInTable) append(" table")
            if (isDocumentStart) append(" documentStart")
            for (level in 0 until containerDepth) {
                append(
                    if (isQuote(level)) " >" else " li@${listContentColumn(level)}",
                )
            }
            append(')')
        }

    /** The multi-line leaf blocks a line can end inside. */
    public enum class BlockMode {
        /** Not inside a multi-line leaf block. */
        NORMAL,

        /** Inside a ```` ``` ```` or `~~~` fenced code block. */
        FENCED_CODE,

        /** Inside `---` front matter at the top of the document. */
        FRONT_MATTER,

        /** Inside a `$$` display-math block. */
        MATH,

        /** Inside an HTML block; see [htmlBlockType]. */
        HTML,
        ;

        internal companion object {
            val ALL: Array<BlockMode> = entries.toTypedArray()
        }
    }

    /** The states every document starts from. */
    public companion object {
        /** Deeper containers are read as text: the state stays two longs, and real documents stop well short. */
        public const val MAX_CONTAINER_DEPTH: Int = 8

        internal const val MODE_MASK = 0x7L
        internal const val HTML_TYPE_SHIFT = 3
        internal const val HTML_TYPE_MASK = 0x7L
        internal const val FENCE_TILDE = 1L shl 6
        internal const val DOCUMENT_START_BIT = 1L shl 7
        internal const val FENCE_LENGTH_SHIFT = 8
        internal const val BYTE = 0xFFL
        internal const val PARAGRAPH = 1L shl 16
        internal const val PIPE_CELLS_SHIFT = 17
        internal const val PIPE_CELLS_MASK = 0x3FL
        internal const val TABLE = 1L shl 23
        internal const val TOML = 1L shl 24

        /** Container byte for a block quote; list items store their content column, 1..254. */
        internal const val QUOTE = 0xFF
        internal const val MAX_LIST_COLUMN = 0xFE
        private const val BITS_PER_LEVEL = 8
        private const val HASH_PRIME = 31L

        /** The state before line 0: the only place front matter may open. */
        public val DOCUMENT_START: LineState = LineState(DOCUMENT_START_BIT, 0, null)

        /** The state between ordinary blocks: no container, no open block. */
        public val NORMAL: LineState = LineState(0, 0, null)

        internal fun containerAt(
            containers: Long,
            level: Int,
        ): Int = if (level >= MAX_CONTAINER_DEPTH) 0 else ((containers ushr (level * BITS_PER_LEVEL)) and BYTE).toInt()

        /** [containers] with only the outermost [depth] kept. */
        internal fun truncate(
            containers: Long,
            depth: Int,
        ): Long =
            if (depth >=
                MAX_CONTAINER_DEPTH
            ) {
                containers
            } else {
                containers and ((1L shl (depth * BITS_PER_LEVEL)) - 1)
            }

        /** [containers] with [value] pushed at [depth]; unchanged when the stack is full. */
        internal fun push(
            containers: Long,
            depth: Int,
            value: Int,
        ): Long =
            if (depth >=
                MAX_CONTAINER_DEPTH
            ) {
                containers
            } else {
                containers or (value.toLong() shl (depth * BITS_PER_LEVEL))
            }

        /**
         * A state with these fields, reusing [reuse] when it is equal. Most lines
         * end in the state they started in, so this keeps a document's states to
         * a handful of shared objects.
         */
        internal fun of(
            bits: Long,
            containers: Long,
            fenceInfo: String?,
            reuse: LineState,
        ): LineState =
            when {
                bits == reuse.bits && containers == reuse.containers && fenceInfo == reuse.fenceInfo -> reuse
                bits == 0L && containers == 0L && fenceInfo == null -> NORMAL
                else -> LineState(bits, containers, fenceInfo)
            }
    }
}
