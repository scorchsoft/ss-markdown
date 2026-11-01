package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.markdown.LineKind
import com.scorchsoft.ssmarkdown.markdown.NanoClock
import com.scorchsoft.ssmarkdown.text.PieceTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The budgeted main-thread pass and the idle slices, with a fake clock and a manual frame pump. */
class HighlightSchedulerTest {
    private val queued = ArrayDeque<() -> Unit>()
    private var now = 0L
    private var repaints = 0

    /** Every read of the clock advances it by one millisecond: a line "costs" 1 ms. */
    private val clock = NanoClock { now.also { now += 1_000_000 } }

    private val scheduler =
        HighlightScheduler(onRepaint = { repaints++ }, clock = clock, frames = { queued.addLast(it) })

    private fun pump(): Int {
        var frames = 0
        while (queued.isNotEmpty()) {
            queued.removeFirst()()
            frames++
        }
        return frames
    }

    @Test
    fun `the main thread stops at its budget and idle slices finish the job`() {
        val text = PieceTree.of("```\n" + "code\n".repeat(200) + "```\n" + "after\n".repeat(50))
        scheduler.reset(text)
        val done = scheduler.prepare(0..100)
        assertFalse("8 ms cannot reach line 100 at 1 ms a line", done)
        assertTrue(scheduler.tokens.accurateLineCount in 1..20)
        val frames = pump()
        assertTrue("several slices were needed: $frames", frames > 1)
        assertTrue(scheduler.tokens.isComplete)
        assertTrue("visible lines became accurate, so the view was asked to redraw", repaints > 0)
    }

    @Test
    fun `unreached lines still get heuristic tokens`() {
        val text = PieceTree.of("```\n" + "code\n".repeat(100))
        scheduler.reset(text)
        scheduler.prepare(0..0)
        assertFalse(scheduler.tokens.isAccurate(40))
        // Guessed from line 0's end state, which is within the heuristic look-back.
        assertEquals(LineKind.CODE, scheduler.resultAt(40).kind)
    }

    @Test
    fun `an edit re-tokenises from the edited line`() {
        val text = PieceTree.of("a\nb\nc\n")
        scheduler.reset(text)
        scheduler.prepare(0..3)
        pump()
        text.insert(2, "```\n")
        scheduler.onEdit(LineChange(firstLine = 1, removedBreaks = 0, addedBreaks = 1))
        scheduler.prepare(0..4)
        pump()
        assertEquals(LineKind.CODE, scheduler.resultAt(2).kind)
    }

    @Test
    fun `an idle slice and the pre-draw pass share one 8 ms frame budget (PERF-D)`() {
        val text = PieceTree.of("```\n" + "code\n".repeat(2_000))
        scheduler.reset(text)
        scheduler.prepare(0..0)
        // A new frame: its idle slice runs first, then the draw asks for the visible lines.
        now += HighlightScheduler.FRAME_NANOS
        val spentBefore = scheduler.nanosSpent
        val linesBefore = scheduler.tokens.linesTokenized
        queued.removeFirst()()
        scheduler.prepare(0..1_500)
        val spent = scheduler.nanosSpent - spentBefore
        val lines = scheduler.tokens.linesTokenized - linesBefore
        // Before: a 4 ms slice plus a full 8 ms pre-draw pass, 12 lines at 1 ms each.
        assertTrue("$spent ns in one frame", spent <= HighlightScheduler.MAIN_BUDGET_NANOS + ONE_LINE)
        assertTrue("$lines lines in one frame", lines in 1..MAX_LINES_PER_FRAME)
        // The next frame has the whole budget again.
        now += HighlightScheduler.FRAME_NANOS
        val nextBefore = scheduler.tokens.linesTokenized
        scheduler.prepare(0..1_500)
        assertTrue(scheduler.tokens.linesTokenized - nextBefore >= MAX_LINES_PER_FRAME - 1)
    }

    @Test
    fun `a pre-draw pass that used the whole budget pushes the idle slice to the next frame (PERF-D)`() {
        val text = PieceTree.of("```\n" + "code\n".repeat(2_000))
        scheduler.reset(text)
        scheduler.prepare(0..1_500)
        val lines = scheduler.tokens.linesTokenized
        queued.removeFirst()()
        assertEquals("no slice work in a spent frame", lines, scheduler.tokens.linesTokenized)
        assertEquals("the slice is queued again", 1, queued.size)
    }

    private companion object {
        /** The fake clock's cost of one line, plus the read that measures it. */
        const val ONE_LINE = 2_000_000L

        /** 8 ms at 1 ms a line. */
        const val MAX_LINES_PER_FRAME = 8L
    }
}
