package com.scorchsoft.ssmarkdown.editor

import android.view.Choreographer
import com.scorchsoft.ssmarkdown.markdown.IncrementalTokenizer
import com.scorchsoft.ssmarkdown.markdown.LineResult
import com.scorchsoft.ssmarkdown.markdown.LineSource
import com.scorchsoft.ssmarkdown.markdown.NanoClock
import com.scorchsoft.ssmarkdown.markdown.asLineSource
import com.scorchsoft.ssmarkdown.text.PieceTree

/**
 * Keeps the syntax colours of the Code pane current within a time budget
 * (robustness.md, "Lazy, with a time budget"):
 *
 * - Before each frame, [prepare] tokenises accurately up to the last
 *   visible line within what is left of the frame's budget.
 * - Whatever is left, up to about [LOOKAHEAD_CHARS] past the viewport,
 *   continues in slices of at most [IDLE_BUDGET_NANOS], one per
 *   Choreographer frame, until the pass converges; [onRepaint] is called
 *   whenever a slice made visible lines accurate, so their guessed
 *   colours are corrected.
 * - Lines the pass has not reached are drawn with heuristic tokens
 *   ([IncrementalTokenizer.tokenizeHeuristically]).
 *
 * Both passes draw on **one** [FrameBudget] of [MAIN_BUDGET_NANOS] (8 ms,
 * quality-bar §4) per frame. An idle slice runs in the frame's animation
 * callbacks, before the draw that calls [prepare], so before this they
 * could add up to 12 ms in one frame (performance review PERF-D). The
 * outline, the other tokenizer, runs on a background snapshot and spends
 * nothing here. [prepare] always tokenises at least one line, so the
 * visible lines always make progress, even when the slice used the budget.
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
    private val budget = FrameBudget(clock, MAIN_BUDGET_NANOS, FRAME_NANOS)

    /** Main-thread tokenising charged so far, both passes: the PERF-D regression statistic. */
    val nanosSpent: Long get() = budget.totalSpent

    /** The tokenizer, for the outline and tests. */
    val tokens: IncrementalTokenizer get() = tokenizer

    /** Starts over on a new document. */
    fun reset(newText: PieceTree) {
        text = newText
        tokenizer = IncrementalTokenizer(cappedSource(newText))
        visible = 0..0
    }

    /**
     * The memory governor's "tokenizer cache" step: drops every cached
     * token. Per-line states stay, so the next frame re-tokenises only the
     * lines it draws.
     */
    fun trimCache() = tokenizer.trimCache()

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
        val allowance = budget.remaining()
        val started = clock.nanoTime()
        val done = tokenizer.tokenizeUntil(lines.last, allowance, clock)
        budget.charge(clock.nanoTime() - started)
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
        val allowance = minOf(budget.remaining(), IDLE_BUDGET_NANOS)
        if (allowance <= 0) {
            // This frame's 8 ms went on the visible lines: try again next frame.
            queueSlice()
            return
        }
        val started = clock.nanoTime()
        val done = tokenizer.continueInIdle(allowance, clock, limit)
        budget.charge(clock.nanoTime() - started)
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
        /** Main-thread tokenising per frame, both passes together (quality-bar §4). */
        const val MAIN_BUDGET_NANOS: Long = 8_000_000

        /** One idle slice at most: half a 120 Hz frame, so scrolling stays smooth while colours converge. */
        const val IDLE_BUDGET_NANOS: Long = 4_000_000

        /** The window one budget covers: a 60 Hz frame, the slowest display we draw for. */
        const val FRAME_NANOS: Long = 16_666_667

        /** Idle tokenising stops this many chars past the viewport. */
        const val LOOKAHEAD_CHARS: Long = 100_000

        /**
         * Lines as the tokenizer sees them: cut at the tokenised prefix, so
         * a 5 MB line never becomes a 5 MB string just to colour 10,000 chars.
         */
        fun cappedSource(text: PieceTree): LineSource = text.asLineSource()
    }
}

/**
 * The main thread's tokenising allowance for one frame, shared by every
 * pass that spends it (performance review PERF-D). A window opens at the
 * first spend and lasts [frameNanos]; within it, the passes together get
 * [perFrameNanos]. Clock-based rather than tied to a vsync timestamp, so
 * it is the same code on a device and on the host, and any [frameNanos]
 * window holds at most one budget of work plus one line.
 */
internal class FrameBudget(
    private val clock: NanoClock,
    private val perFrameNanos: Long,
    private val frameNanos: Long,
) {
    private var windowStart = 0L
    private var windowOpen = false
    private var spent = 0L

    /** Everything ever charged, for tests. */
    var totalSpent: Long = 0
        private set

    /** What is left of this frame's allowance; a new window starts with all of it. */
    fun remaining(): Long {
        val now = clock.nanoTime()
        if (!windowOpen || now - windowStart >= frameNanos) {
            windowOpen = true
            windowStart = now
            spent = 0
        }
        return (perFrameNanos - spent).coerceAtLeast(0)
    }

    /** Records [nanos] of work against the current window. */
    fun charge(nanos: Long) {
        spent += nanos
        totalSpent += nanos
    }
}
