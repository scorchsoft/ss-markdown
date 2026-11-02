package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.find.FindAction
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
 * Find never scans the document on the main thread, and typing with the
 * bar open costs one search per burst, not one per key (performance
 * review PERF-8). Counts, not timings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FindControllerTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun EditorFixture.type(
        at: Int,
        text: String,
    ) {
        val doc = vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(at, "", text)), TextSelection.caret(at + text.length))
    }

    private fun EditorFixture.matches() =
        vm.uiState.value.find
            ?.matches

    @Test
    fun `a burst of typing with the bar open runs one search, after it settles`() {
        val f = EditorFixture(temp.root, dispatcher)
        f.vm.openDocument(f.file("f.md", "needle\n" + "hay\n".repeat(1_000)))
        f.vm.onAction(EditorAction.Find(FindAction.Open))
        f.vm.onAction(EditorAction.Find(FindAction.Query("needle")))
        assertEquals(1, f.matches()?.size)
        val before = f.vm.find.searchesStarted

        repeat(KEYS) { f.type(0, "x") }
        // Before PERF-8: one whole-document scan per key, on the main thread.
        assertEquals(before, f.vm.find.searchesStarted)

        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(before + 1, f.vm.find.searchesStarted)
        assertEquals(KEYS, f.matches()?.single()?.start)
    }

    @Test
    fun `the scan runs on the compute dispatcher, not the caller's thread`() {
        val compute = StandardTestDispatcher(dispatcher.scheduler)
        val f = EditorFixture(temp.root, dispatcher, compute = compute)
        f.vm.openDocument(f.file("f.md", "a a a\n"))
        f.vm.onAction(EditorAction.Find(FindAction.Open))
        f.vm.onAction(EditorAction.Find(FindAction.Query("a")))
        assertTrue("no matches until the background search lands", f.matches().isNullOrEmpty())
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, f.matches()?.size)
    }

    @Test
    fun `a newer query cancels the search in flight, and replace waits for the fresh matches`() {
        val compute = StandardTestDispatcher(dispatcher.scheduler)
        val f = EditorFixture(temp.root, dispatcher, compute = compute)
        f.vm.openDocument(f.file("f.md", "a b a b\n"))
        f.vm.onAction(EditorAction.Find(FindAction.Open))
        f.vm.onAction(EditorAction.Find(FindAction.Query("a")))
        f.vm.onAction(EditorAction.Find(FindAction.Query("b")))
        f.vm.onAction(EditorAction.Find(FindAction.Replacement("c")))
        f.vm.onAction(EditorAction.Find(FindAction.ReplaceAll))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "a c a c\n",
            f.vm.active
                ?.text
                ?.text
                .toString(),
        )
    }

    private companion object {
        const val KEYS = 20
    }
}
