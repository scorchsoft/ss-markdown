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
}
