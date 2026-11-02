package com.scorchsoft.ssmarkdown.document

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.ui.EditorFor
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * edge-cases "Same file open twice (multi-window, or open again from the
 * file manager)". Every window of the app binds to the one process-wide
 * editor and store, so a second window, or the file manager opening the
 * file again, must join the buffer already open: never a second copy that
 * could save over the first, never a reload that drops unsaved edits, and
 * never a window count that keeps the entry alive after it closes (CC-12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SameFileTwiceTest {
    @get:Rule val temp = TemporaryFolder()

    @get:Rule val rule = createComposeRule()

    @Test
    fun `opening a file with unsaved edits again joins the same buffer and keeps the edits`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val uri = f.file("notes.md", "# Notes\n")
        f.vm.openDocument(uri)
        val first = f.vm.active ?: error("nothing open")
        first.surface.apply(listOf(EditOp(0, "", "Unsaved. ")), TextSelection.caret(9))

        // The file manager (or a second window) opens the same file.
        f.vm.openDocument(uri, fromAnotherApp = true, readOnlySource = null)

        val second = f.vm.active ?: error("nothing open")
        assertSame(first.open, second.open)
        assertSame(first.open, f.store[DocumentUri(uri)])
        assertEquals("Unsaved. # Notes\n", second.text.snapshot().toString())
        assertTrue(second.open.isDirty)
        assertEquals("# Notes\n", f.disk("notes.md"))
    }

    @Test
    fun `two windows on the same file show one buffer, and an edit in either is in both`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val uri = f.file("notes.md", "# Notes\n")
        f.vm.openDocument(uri)
        rule.setContent {
            // Two windows side by side, as in split-screen: both bind to the process's editor.
            Row {
                listOf(1, 2).forEach { _ ->
                    Box(Modifier.weight(1f)) {
                        val state by f.vm.uiState.collectAsState()
                        Themed(dark = false) { EditorFor(state, f.vm::onAction) }
                    }
                }
            }
        }
        val doc = f.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(0, "", "Both. ")), TextSelection.caret(6))
        rule.waitForIdle()
        // Each window marks the file unsaved (the header's word or dot), so there are at least two.
        val unsaved =
            hasContentDescription("Unsaved", substring = true, ignoreCase = true) or
                hasText("Unsaved", substring = true, ignoreCase = true)
        assertTrue(rule.onAllNodes(unsaved).fetchSemanticsNodes().size >= 2)
        assertEquals(
            "Both. # Notes\n",
            f.store[DocumentUri(uri)]
                ?.document
                ?.text
                .toString(),
        )
    }

    @Test
    fun `the entry is released once the last window lets go`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val uri = f.file("notes.md", "# Notes\n")
        f.vm.openDocument(uri)
        f.vm.openDocument(uri, fromAnotherApp = true, readOnlySource = null)
        f.vm.openDocument(f.file("other.md", "# Other\n"))

        // Clean and no longer shown: nothing keeps a phantom second window open.
        assertEquals(null, f.store[DocumentUri(uri)])
    }
}
