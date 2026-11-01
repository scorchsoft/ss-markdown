package com.scorchsoft.ssmarkdown.editor

import android.view.Choreographer
import com.scorchsoft.ssmarkdown.markdown.IncrementalTokenizer
import com.scorchsoft.ssmarkdown.markdown.LineResult
import com.scorchsoft.ssmarkdown.markdown.LineSource
import com.scorchsoft.ssmarkdown.markdown.LineTokenizer
import com.scorchsoft.ssmarkdown.markdown.NanoClock
import com.scorchsoft.ssmarkdown.text.PieceTree

/**
 * Keeps the syntax colours of the Code pane current within a time budget
 * (robustness.md, "Lazy, with a time budget"):
 *
 * - Before each frame, [prepare] tokenises accurately up to the last
 *   visible line for at most [MAIN_BUDGET_NANOS] (8 ms).
 * - Whatever is left, up to about [LOOKAHEAD_CHARS] past the viewport,
 *   continues in [IDLE_BUDGET_NANOS] slices, one per Choreographer frame,
 *   until the pass converges; [onRepaint] is called whenever a slice made
 *   visible lines accurate, so their guessed colours are corrected.
 * - Lines the pass has not reached are drawn with heuristic tokens
 *   ([IncrementalTokenizer.tokenizeHeuristically]).
 *
 * Main thread only, like the document.
 */
class HighlightScheduler(
    /** Called when lines on screen got better colours and should be redrawn. */
    private val onRepaint: () -> Unit,
    private val clock: NanoClock = NanoClock.SYSTEM,
    private val frames: FrameScheduler = FrameScheduler.CHOREOGRAPHER,
) {
    /** Schedules the next idle slice; Choreographer on a device, a manual pump in tests. */
    fun interface FrameScheduler {
        /** Runs [action] once, at the next frame. */
        fun post(action: () -> Unit)

        /** The platform's frame clock. */
        companion object {
            /** Posts to `Choreographer.getInstance()` of the calling (main) thread. */
            val CHOREOGRAPHER: FrameScheduler =
                FrameScheduler { action -> Choreographer.getInstance().postFrameCallback { action() } }
        }
    }

    private var text: PieceTree = PieceTree.empty()
    private var tokenizer = IncrementalTokenizer(cappedSource(text))
    private var visible = 0..0
    private var sliceQueued = false

    /** The tokenizer, for the outline and tests. */
    val tokens: IncrementalTokenizer get() = tokenizer

    /** Starts over on a new document. */
    fun reset(newText: PieceTree) {
        text = newText
        tokenizer = IncrementalTokenizer(cappedSource(newText))
        visible = 0..0
    }

    /** Follows an edit, in the session's terms. */
    fun onEdit(change: LineChange) {
        tokenizer.invalidate(change.firstLine, change.removedBreaks + 1, change.addedBreaks + 1)
    }

    /**
     * Makes [lines] (the visible ones) as accurate as [MAIN_BUDGET_NANOS]
     * allows, and queues idle slices for the rest. Returns true when every
     * visible line is accurate.
     */
    fun prepare(lines: IntRange): Boolean {
        visible = lines
        val done = tokenizer.tokenizeUntil(lines.last, MAIN_BUDGET_NANOS, clock)
        if (!tokenizer.isAccurate(lookaheadLimit())) queueSlice()
        return done
    }

    /** The best tokens for [line] right now. */
    fun resultAt(line: Int): LineResult = tokenizer.resultAt(line)

    private fun queueSlice() {
        if (sliceQueued) return
        sliceQueued = true
        frames.post(::runSlice)
    }

    private fun runSlice() {
        sliceQueued = false
        if (tokenizer.lineCount != text.lineCount) return // A reset or edit is mid-flight; the next prepare() requeues.
        val visibleWasAccurate = tokenizer.isAccurate(visible.last)
        val limit = lookaheadLimit()
        val done = tokenizer.continueInIdle(IDLE_BUDGET_NANOS, clock, limit)
        if (!visibleWasAccurate && tokenizer.isAccurate(visible.last)) onRepaint()
        if (!done) queueSlice()
    }

    /** The last line within [LOOKAHEAD_CHARS] of the end of the viewport. */
    private fun lookaheadLimit(): Int {
        val last = visible.last.coerceAtMost(text.lineCount - 1)
        val offset = minOf(text.length.toLong(), text.lineEndOffset(last).toLong() + LOOKAHEAD_CHARS).toInt()
        return text.positionAt(offset).line
    }

    /** Budgets from robustness.md. */
    companion object {
        /** Main-thread budget before a frame. */
        const val MAIN_BUDGET_NANOS: Long = 8_000_000

        /** One idle slice: half a 120 Hz frame, so scrolling stays smooth while colours converge. */
        const val IDLE_BUDGET_NANOS: Long = 4_000_000

        /** Idle tokenising stops this many chars past the viewport. */
        const val LOOKAHEAD_CHARS: Long = 100_000

        /**
         * Lines as the tokenizer sees them: cut at the tokenised prefix, so
         * a 5 MB line never becomes a 5 MB string just to colour 10,000 chars.
         */
        fun cappedSource(text: PieceTree): LineSource =
            object : LineSource {
                override val lineCount: Int get() = text.lineCount

                override fun lineText(line: Int): CharSequence {
                    val start = text.lineStartOffset(line)
                    val end = minOf(text.lineEndOffset(line), start + LineTokenizer.MAX_TOKENIZED_COLUMNS)
                    return text.getText(start, end)
                }
            }
    }
}
