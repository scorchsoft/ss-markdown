package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The outline follows every edit incrementally, whichever surface made it,
 * and tokenises on its worker, not the main thread (performance review
 * PERF-A, PERF-D). Counts lines tokenised, never time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutlineTrackerTest {
    @get:Rule val temp = TemporaryFolder()

    private val text =
        (0 until LINES).joinToString("\n") { line ->
            if (line % (LINES / HEADINGS) == 0) "# Heading ${line / (LINES / HEADINGS)}" else "text $line"
        }

    @Test
    fun `an edit with no Code view re-reads a line or two and never empties the outline (PERF-A)`() {
        val dispatcher = UnconfinedTestDispatcher()
        val f = EditorFixture(temp.root, dispatcher)
        f.vm.openDocument(f.file("long.md", text))
        dispatcher.scheduler.advanceUntilIdle()
        val doc = checkNotNull(f.vm.active)
        assertEquals(HEADINGS, doc.outline.headings().size)
        val before = doc.outline.linesTokenized
        // Preview mode: no Code view, so the edit goes through the buffer surface.
        val end = doc.text.text.length
        doc.surface.apply(listOf(EditOp(end, "", "x")), TextSelection.caret(end + 1))
        dispatcher.scheduler.advanceUntilIdle()
        val tokenized = doc.outline.linesTokenized - before
        // Before: invalidateAll() dropped every heading and re-read all 100,000 lines.
        assertTrue("$tokenized lines re-tokenised for one char", tokenized <= 2)
        assertEquals(HEADINGS, doc.outline.headings().size)
        assertEquals(listOf("Heading 49"), doc.outline.breadcrumbAt(LINES - 1).map { it.text })
    }

    @Test
    fun `the main thread keeps a line-true outline while the worker has not run yet (PERF-D)`() {
        val dispatcher = StandardTestDispatcher()
        val f = EditorFixture(temp.root, dispatcher)
        f.vm.openDocument(f.file("long.md", text))
        dispatcher.scheduler.advanceUntilIdle()
        val doc = checkNotNull(f.vm.active)
        val before = doc.outline.linesTokenized
        // Ten line breaks inside line 1, and the worker has not been given a turn.
        val inLineOne = "# Heading 0\nte".length
        repeat(BREAKS) { doc.surface.apply(listOf(EditOp(inLineOne, "", "\n")), TextSelection.caret(inLineOne)) }
        assertEquals("no tokenising happened on this thread", before, doc.outline.linesTokenized)
        val second = LINES / HEADINGS + BREAKS
        assertEquals(
            listOf(0, second),
            doc.outline
                .headings()
                .take(2)
                .map { it.line },
        )
        assertEquals(HEADINGS, doc.outline.headings().size)
        // Its turn: the published outline agrees, and only the split lines were read again.
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(0, second),
            doc.outline
                .headings()
                .take(2)
                .map { it.line },
        )
        assertTrue(doc.outline.linesTokenized - before <= BREAKS * 2L + 2)
    }

    private companion object {
        const val LINES = 100_000
        const val HEADINGS = 50
        const val BREAKS = 10
    }
}
