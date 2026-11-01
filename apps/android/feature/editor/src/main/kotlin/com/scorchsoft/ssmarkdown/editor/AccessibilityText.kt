package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.text.PieceTree

/** How far an accessibility "next/previous at granularity" step moves. */
enum class Granularity {
    CHARACTER,
    WORD,
    LINE,
}

/**
 * The text maths behind the editor's accessibility tree, with no Android
 * types so it is tested on the host:
 *
 * - the editable node's text is a window of at most [MAX_NODE_CHARS]
 *   (100K, the parcel-safe limit from quality-bar) centred on the
 *   selection, never the whole document;
 * - each visible line's virtual node carries at most [MAX_LINE_CHARS];
 * - [step] finds the span a screen reader traverses when it moves the
 *   cursor by character (grapheme), word or line.
 */
class AccessibilityText(
    private val words: WordFinder = WordFinder(),
) {
    /** The editable node's text window for [session]. */
    fun nodeWindow(session: EditingSession): TextWindow = session.windowAround(MAX_NODE_CHARS)

    /** The text of [line] for its virtual node, capped at [MAX_LINE_CHARS]. */
    fun lineText(
        text: PieceTree,
        line: Int,
    ): CharSequence {
        val start = text.lineStartOffset(line)
        return text.getText(start, minOf(text.lineEndOffset(line), start + MAX_LINE_CHARS))
    }

    /**
     * The span `[first, last]` a step of [granularity] from [offset] moves
     * across, forwards or back, or null at the document's edge. The caret
     * lands on its far end (its end when [forward], its start otherwise).
     */
    fun step(
        session: EditingSession,
        offset: Int,
        granularity: Granularity,
        forward: Boolean,
    ): IntRange? {
        val text = session.document.text
        val atEdge = if (forward) offset >= text.length else offset <= 0
        if (atEdge) return null
        val target =
            when (granularity) {
                Granularity.CHARACTER -> {
                    if (forward) session.graphemes.next(text, offset) else session.graphemes.previous(text, offset)
                }

                Granularity.WORD -> {
                    if (forward) words.nextWordEnd(text, offset) else words.previousWordStart(text, offset)
                }

                Granularity.LINE -> {
                    lineStep(text, offset, forward)
                }
            }
        return if (forward) offset until target else target until offset
    }

    private fun lineStep(
        text: PieceTree,
        offset: Int,
        forward: Boolean,
    ): Int {
        val line = text.positionAt(offset).line
        return if (forward) {
            val end = text.lineEndOffset(line)
            if (offset < end) end else text.lineEndOffset(line + 1)
        } else {
            val start = text.lineStartOffset(line)
            if (offset > start) start else text.lineStartOffset(line - 1)
        }
    }

    /** Limits. */
    companion object {
        /** Chars in the editable node's text (quality-bar: "Accessibility node text ≤ 100K chars"). */
        const val MAX_NODE_CHARS: Int = 100_000

        /** Chars in one line's virtual node. */
        const val MAX_LINE_CHARS: Int = 10_000
    }
}
