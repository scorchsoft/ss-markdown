package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A long session of scattered edits does not leave the piece tree fragmented (performance review PERF-F). */
@OptIn(ExperimentalCoroutinesApi::class)
class TreeCompactorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `scattered edits are compacted off the main thread, and the text is unchanged`() {
        val dispatcher = UnconfinedTestDispatcher()
        val f = EditorFixture(temp.root, dispatcher)
        f.vm.openDocument(f.file("notes.md", "line\n".repeat(2_000)))
        val doc = checkNotNull(f.vm.active)
        val expected = StringBuilder(doc.text.text.toString())
        repeat(EDITS) { i ->
            // Every edit lands somewhere new, so each one splits a piece.
            val at = (i * 7_919) % (expected.length + 1)
            doc.surface.apply(listOf(EditOp(at, "", "x")), TextSelection.caret(at + 1))
            expected.insert(at, 'x')
        }
        dispatcher.scheduler.advanceUntilIdle()
        val tree = doc.text.text
        // Before: compact() was never called outside the benchmark, so this ended near 2 x 3,000 pieces.
        assertTrue("${doc.compactor.compactions} compactions", doc.compactor.compactions >= 1)
        assertFalse("${tree.pieceCount} pieces", tree.needsCompaction)
        assertEquals(expected.toString(), tree.toString())
    }

    private companion object {
        const val EDITS = 3_000
    }
}
