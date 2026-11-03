package com.scorchsoft.ssmarkdown.share

import com.scorchsoft.ssmarkdown.settings.AppendPosition
import com.scorchsoft.ssmarkdown.settings.AppendPrefix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The duplicate check before an append (ux-spec "Share into SS Markdown"):
 * is the shared text already where [AppendPlanner] would put it? It reads
 * only a window at that place, never the whole file.
 */
class AppendDuplicateTest {
    private fun appended(
        existing: String,
        shared: String,
        position: AppendPosition,
        prefix: AppendPrefix = AppendPrefix.None,
    ): String {
        val plan = AppendPlanner.plan(existing, shared, position, prefix, "3 Nov 2026, 14:02")
        return existing.substring(0, plan.offset) + plan.text + existing.substring(plan.offset)
    }

    @Test
    fun `text the planner just added at the end is found there`() {
        val file = appended("# Notes\n\nOlder notes.\n", QUOTE, AppendPosition.Bottom)
        assertTrue(AppendPlanner.alreadyThere(file, QUOTE, AppendPosition.Bottom))
        val dated = appended("# Notes\n", QUOTE, AppendPosition.Bottom, AppendPrefix.DateHeading)
        assertTrue(AppendPlanner.alreadyThere(dated, QUOTE, AppendPosition.Bottom))
    }

    @Test
    fun `text the planner just added at the top is found there`() {
        val file = appended("# Notes\n\nOlder notes.\n", QUOTE, AppendPosition.Top)
        assertTrue(AppendPlanner.alreadyThere(file, QUOTE, AppendPosition.Top))
        val dated = appended("# Notes\n", QUOTE, AppendPosition.Top, AppendPrefix.DateHeading)
        assertTrue(AppendPlanner.alreadyThere(dated, QUOTE, AppendPosition.Top))
    }

    @Test
    fun `the check looks only where this append would go`() {
        val atEnd = appended("# Notes\n" + "x".repeat(2_000) + "\n", QUOTE, AppendPosition.Bottom)
        assertTrue(AppendPlanner.alreadyThere(atEnd, QUOTE, AppendPosition.Bottom))
        assertFalse(AppendPlanner.alreadyThere(atEnd, QUOTE, AppendPosition.Top))
        val atTop = appended("# Notes\n", QUOTE, AppendPosition.Top) + "y".repeat(2_000) + "\n"
        assertFalse(AppendPlanner.alreadyThere(atTop, QUOTE, AppendPosition.Bottom))
    }

    @Test
    fun `line endings and surrounding blank space do not hide a duplicate`() {
        val crlfFile = "# Notes\r\n\r\nLine one\r\nLine two\r\n"
        assertTrue(AppendPlanner.alreadyThere(crlfFile, "Line one\nLine two", AppendPosition.Bottom))
        val lfFile = "# Notes\n\nLine one\nLine two\n"
        assertTrue(AppendPlanner.alreadyThere(lfFile, "\r\n  Line one\r\nLine two\r\n\r\n", AppendPosition.Bottom))
    }

    @Test
    fun `near-miss text is not a duplicate`() {
        val file = appended("# Notes\n", QUOTE, AppendPosition.Bottom)
        // A shorter piece of the same line, one more character, one changed character.
        assertFalse(AppendPlanner.alreadyThere(file, "A snippet from", AppendPosition.Bottom))
        assertFalse(AppendPlanner.alreadyThere(file, "$QUOTE.", AppendPosition.Bottom))
        assertFalse(AppendPlanner.alreadyThere(file, "A snippet from Chrame", AppendPosition.Bottom))
        // The same words inside a longer sentence are someone's own writing, not this share.
        val inSentence = "# Notes\n\nHe said $QUOTE yesterday.\n"
        assertFalse(AppendPlanner.alreadyThere(inSentence, QUOTE, AppendPosition.Bottom))
    }

    @Test
    fun `a file without the text is not a duplicate, and blank text never is`() {
        assertFalse(AppendPlanner.alreadyThere("# Notes\n", QUOTE, AppendPosition.Bottom))
        assertFalse(AppendPlanner.alreadyThere("", QUOTE, AppendPosition.Top))
        assertFalse(AppendPlanner.alreadyThere("# Notes\n\n   \n", "   \n", AppendPosition.Bottom))
    }

    @Test
    fun `a large file is checked by reading only the window at the end or the top`() {
        val bottom = WindowOnly(LARGE, "\n\n$QUOTE\n", atEnd = true)
        assertTrue(AppendPlanner.alreadyThere(bottom, QUOTE, AppendPosition.Bottom))
        assertTrue("read ${bottom.read} chars", bottom.read in 1..QUOTE.length + WINDOW_SLACK)
        val top = WindowOnly(LARGE, "$QUOTE\n\n", atEnd = false)
        assertTrue(AppendPlanner.alreadyThere(top, QUOTE, AppendPosition.Top))
        assertTrue("read ${top.read} chars", top.read in 1..QUOTE.length + WINDOW_SLACK)
    }

    /**
     * A [length]-char file of 'x' with [edge] at its end or its start. Any
     * char further than [WINDOW_SLACK] past the shared text from that edge
     * fails the test when read, and [read] counts the chars read.
     */
    private class WindowOnly(
        override val length: Int,
        private val edge: String,
        private val atEnd: Boolean,
    ) : CharSequence {
        var read = 0
        private val window =
            if (atEnd) length - QUOTE.length - WINDOW_SLACK until length else 0 until QUOTE.length + WINDOW_SLACK

        override fun get(index: Int): Char {
            check(index in window) { "read outside the window: $index" }
            read++
            val e = if (atEnd) index - (length - edge.length) else index
            return if (e in edge.indices) edge[e] else 'x'
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence =
            buildString(endIndex - startIndex) { for (i in startIndex until endIndex) append(this@WindowOnly[i]) }

        override fun toString(): String = error("the whole file was copied")
    }

    private companion object {
        const val QUOTE = "A snippet from Chrome"
        const val LARGE = 50_000_000

        /** The planner's window past the text itself: about 512 chars, plus a CR per line. */
        const val WINDOW_SLACK = 600
    }
}
