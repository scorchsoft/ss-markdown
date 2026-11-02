package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Whole-document copies and diffs for the conflict sheet's Compare run on
 * the compute dispatcher, never on the thread that tapped (performance
 * review PERF-22): for a 20 MB file each copy is 40 MB of chars.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OffMainCopiesTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun `compare in a conflict copies and diffs off the main thread`() {
        val compute = StandardTestDispatcher(dispatcher.scheduler)
        val f = EditorFixture(temp.root, dispatcher, compute = compute)
        f.vm.openDocument(f.file("c.md", "one\ntwo\n"))
        dispatcher.scheduler.advanceUntilIdle()
        val doc = f.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(0, "", "zero\n")), TextSelection.caret(5))
        f.externalWrite("c.md", "one\ntwo\nthree\n")
        f.vm.onAction(EditorAction.SaveTapped)
        dispatcher.scheduler.advanceUntilIdle()

        f.vm.conflicts.compare()
        assertNull("nothing was copied or diffed on the caller's thread", f.vm.conflicts.compare.value)
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(f.vm.conflicts.compare.value)
    }
}
