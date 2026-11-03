package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.LineKind
import com.scorchsoft.ssmarkdown.markdown.LineResult
import com.scorchsoft.ssmarkdown.markdown.LineState
import com.scorchsoft.ssmarkdown.markdown.LineTokenizer

/** Where a line sits relative to a fenced code block, as the tokenizer sees it. */
public enum class FenceRole {
    /** Not part of a fenced code block. */
    OUTSIDE,

    /** The ```` ``` ```` or `~~~` line that opens a block. */
    OPENING_FENCE,

    /** A line of code between the fences. */
    INSIDE,

    /** The fence that closes a block. */
    CLOSING_FENCE,
    ;

    /** True for every line that belongs to a fenced block, fences included. */
    public val isCode: Boolean get() = this != OUTSIDE

    /** Mapping from tokenizer results. */
    public companion object {
        /**
         * The role of a line that the tokenizer read from [start] (the end
         * state of the line above) into [result]. This is how an editor with
         * an `IncrementalTokenizer` answers [FenceStates.roleAt] without a
         * second scan.
         */
        public fun of(
            start: LineState,
            result: LineResult,
        ): FenceRole {
            val inFence = start.mode == LineState.BlockMode.FENCED_CODE
            return when {
                result.kind == LineKind.CODE_FENCE && inFence -> CLOSING_FENCE
                result.kind == LineKind.CODE_FENCE -> OPENING_FENCE
                inFence && result.kind == LineKind.CODE -> INSIDE
                else -> OUTSIDE
            }
        }
    }
}

/**
 * Answers "is this line fenced code?" for line commands, so a heading or
 * list toggle never rewrites code and the code-block toggle unwraps the
 * block the caret is really in (docs/research-findings.md R12: only the
 * touched node changes, and code is never "formatted").
 *
 * Fence state depends on every line above, so it cannot be read off the
 * lines around the selection. The editor passes one backed by its
 * incremental tokenizer (O(1) per line); [scan] is the self-contained
 * fallback for callers without one.
 */
public fun interface FenceStates {
    /** The role of the line that starts at [lineStart], an offset in the text the command reads. */
    public fun roleAt(lineStart: Int): FenceRole

    /** Ready-made lookups. */
    public companion object {
        /** Every line is outside code: the behaviour of a command that ignores fences. */
        public val NONE: FenceStates = FenceStates { FenceRole.OUTSIDE }

        /**
         * Tokenises [text] lazily with a [LineTokenizer], from the document
         * start when the first query is within [FULL_SCAN_CHARS], otherwise
         * from [WINDOW_CHARS] above it (a heuristic start, as
         * `IncrementalTokenizer.tokenizeHeuristically` does), so a toolbar
         * tap on a huge file stays bounded.
         */
        public fun scan(text: CharSequence): FenceStates = ScannedFenceStates(text)

        /** Above this offset [scan] stops reading from the top and starts a window above the query. */
        public const val FULL_SCAN_CHARS: Int = 2 * 1024 * 1024

        /** How far above the first query a windowed [scan] starts. */
        public const val WINDOW_CHARS: Int = 256 * 1024
    }
}

/** [FenceStates.scan]: tokenises forward on demand and remembers each line's role. */
private class ScannedFenceStates(
    private val text: CharSequence,
) : FenceStates {
    private val tokenizer = LineTokenizer(highlightCode = false)
    private var starts = IntArray(INITIAL)
    private var roles = ByteArray(INITIAL)
    private var count = 0
    private var origin = -1
    private var next = 0
    private var state = LineState.DOCUMENT_START

    override fun roleAt(lineStart: Int): FenceRole {
        if (origin < 0) begin(lineStart)
        if (lineStart < origin) return FenceRole.OUTSIDE
        while (next <= lineStart && next <= text.length && advance()) Unit
        val i = starts.binarySearch(lineStart, 0, count)
        return if (i >= 0) FenceRole.entries[roles[i].toInt()] else FenceRole.OUTSIDE
    }

    private fun begin(first: Int) {
        origin =
            if (first <= FenceStates.FULL_SCAN_CHARS) {
                0
            } else {
                TextLines.lineStart(text, first - FenceStates.WINDOW_CHARS)
            }
        state = if (origin == 0) LineState.DOCUMENT_START else LineState.NORMAL
        next = origin
    }

    /** Tokenises the line at [next]; false once the text is exhausted. */
    private fun advance(): Boolean {
        val end = TextLines.lineEnd(text, next)
        val result = tokenizer.tokenize(text.subSequence(next, end), state)
        record(next, FenceRole.of(state, result))
        state = result.endState
        val more = end < text.length
        next = end + 1
        return more
    }

    private fun record(
        start: Int,
        role: FenceRole,
    ) {
        if (count == starts.size) {
            starts = starts.copyOf(count * 2)
            roles = roles.copyOf(count * 2)
        }
        starts[count] = start
        roles[count] = role.ordinal.toByte()
        count++
    }

    private companion object {
        const val INITIAL = 64
    }
}
