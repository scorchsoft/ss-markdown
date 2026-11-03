package com.scorchsoft.ssmarkdown.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.settings.AppendPosition
import com.scorchsoft.ssmarkdown.settings.RecentFile
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The guard against adding the same text twice (ux-spec "Share into SS
 * Markdown"): sharing text that already sits where Append would put it
 * writes nothing and asks first; and a sheet rebuilt while its append was
 * still writing finds out whether that append landed before offering it
 * again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareDuplicateTest {
    @get:Rule val temp = TemporaryFolder()

    @get:Rule val rule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()

    private val appended = mutableListOf<String>()

    private fun EditorFixture.sheet(
        target: String,
        name: String = "notes.md",
    ): ShareViewModel {
        settings.update { it.copy(recentFiles = listOf(RecentFile(target, name, "", 0))) }
        return ShareViewModel(
            store,
            drafts,
            history,
            settings,
            vm,
            scope,
            onAppended = { appended += it },
        ) { "1 Nov 2026" }
    }

    private fun EditorFixture.share(
        target: String,
        text: String = SNIPPET,
        position: AppendPosition = AppendPosition.Bottom,
    ): ShareViewModel {
        val share = sheet(target)
        share.begin(text, "example.org")
        share.onAction(ShareAction.ChooseTarget(target))
        share.onAction(ShareAction.SetPosition(position))
        share.onAction(ShareAction.Confirm)
        return share
    }

    @Test
    fun `sharing text already at the end of a closed file writes nothing and says so`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n\n$SNIPPET\n")

        val share = f.share(uri)

        assertEquals(SharePhase.AlreadyThere("notes.md", uri, AppendPosition.Bottom), share.state.value.phase)
        assertEquals("# Notes\n\n$SNIPPET\n", f.disk("notes.md"))
        assertTrue("not reported as used", appended.isEmpty())
    }

    @Test
    fun `Add again appends the text exactly once`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n\n$SNIPPET\n")
        val share = f.share(uri)

        share.onAction(ShareAction.AddAgain)

        assertEquals(SharePhase.Done("notes.md", uri), share.state.value.phase)
        assertEquals("# Notes\n\n$SNIPPET\n\n$SNIPPET\n", f.disk("notes.md"))
        // A second tap on a finished sheet does nothing.
        share.onAction(ShareAction.AddAgain)
        share.onAction(ShareAction.Confirm)
        assertEquals("# Notes\n\n$SNIPPET\n\n$SNIPPET\n", f.disk("notes.md"))
    }

    @Test
    fun `sharing text already at the top asks before adding it at the top`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "$SNIPPET\n\n# Notes\n")

        val share = f.share(uri, position = AppendPosition.Top)

        assertEquals(SharePhase.AlreadyThere("notes.md", uri, AppendPosition.Top), share.state.value.phase)
        assertEquals("$SNIPPET\n\n# Notes\n", f.disk("notes.md"))
    }

    @Test
    fun `an open buffer with unsaved edits is checked too, and left as it was`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n")
        f.vm.openDocument(uri)
        val doc = f.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(8, "", "\n$SNIPPET\n")), TextSelection.caret(8))

        val share = f.share(uri)

        assertEquals(SharePhase.AlreadyThere("notes.md", uri, AppendPosition.Bottom), share.state.value.phase)
        assertEquals("# Notes\n\n$SNIPPET\n", doc.text.snapshot().toString())
    }

    @Test
    fun `a clean open buffer is checked`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n\n$SNIPPET\n")
        f.vm.openDocument(uri)

        val share = f.share(uri)

        assertTrue(share.state.value.phase is SharePhase.AlreadyThere)
        assertEquals("# Notes\n\n$SNIPPET\n", f.disk("notes.md"))
    }

    @Test
    fun `different text appends as before, and blank text is never checked`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n\n$SNIPPET\n")

        val share = f.share(uri, "Something else")
        assertEquals(SharePhase.Done("notes.md", uri), share.state.value.phase)

        val blank = f.share(uri, "  \n")
        assertEquals(SharePhase.Done("notes.md", uri), blank.state.value.phase)
    }

    @Test
    fun `a new note is never checked`() {
        val f = EditorFixture(temp.root, dispatcher)
        val share = f.sheet(f.file("notes.md", "# Notes\n\n$SNIPPET\n"))
        share.begin(SNIPPET, "example.org")
        share.onAction(ShareAction.ChooseTarget(null))
        share.onAction(ShareAction.Confirm)

        assertTrue(share.state.value.phase is SharePhase.Done)
    }

    // A sheet rebuilt while its append was still writing (the process died in Working).

    @Test
    fun `a rebuilt sheet whose interrupted append had landed shows it added`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n\n$SNIPPET\n")
        val share = f.sheet(uri)

        share.restoreInterrupted(uri, "notes.md", AppendPosition.Bottom)
        share.begin(SNIPPET, "example.org")

        assertEquals(SharePhase.Done("notes.md", uri), share.state.value.phase)
        assertFalse(share.state.value.interrupted)
        share.onAction(ShareAction.Confirm)
        assertEquals("# Notes\n\n$SNIPPET\n", f.disk("notes.md"))
    }

    @Test
    fun `a rebuilt sheet whose interrupted append had not landed offers Append again, once`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n")
        val share = f.sheet(uri)

        share.restoreInterrupted(uri, "notes.md", AppendPosition.Bottom)
        share.begin(SNIPPET, "example.org")

        val state = share.state.value
        assertEquals(SharePhase.Choosing, state.phase)
        assertTrue("the sheet says the last Append didn't finish", state.interrupted)
        assertEquals(uri, state.target)
        assertEquals("# Notes\n", f.disk("notes.md"))

        share.onAction(ShareAction.Confirm)

        assertEquals(SharePhase.Done("notes.md", uri), share.state.value.phase)
        assertEquals("# Notes\n\n$SNIPPET\n", f.disk("notes.md"))
        assertFalse(share.state.value.interrupted)
    }

    @Test
    fun `a rebuilt sheet whose interrupted new note never finished offers it again`() {
        val f = EditorFixture(temp.root, dispatcher)
        val share = f.sheet(f.file("notes.md", "# Notes\n"))

        share.restoreInterrupted(null, "", AppendPosition.Bottom)
        share.begin(SNIPPET, "example.org")

        val state = share.state.value
        assertEquals(SharePhase.Choosing, state.phase)
        assertEquals(null, state.target)
        assertTrue(state.interrupted)
    }

    @Test
    fun `the sheet says where the text already is and offers Open, Add again and Cancel`() {
        val actions = ArrayList<ShareAction>()
        rule.setContent {
            Themed(dark = false) {
                ShareSheet(
                    ShareUiState(
                        text = SNIPPET,
                        phase = SharePhase.AlreadyThere("notes.md", "u", AppendPosition.Bottom),
                    ),
                ) { actions += it }
            }
        }
        rule.onNodeWithText("This text is already at the end of notes.md.").assertIsDisplayed()
        rule.onNodeWithText("Open notes.md").performClick()
        rule.onNodeWithText("Add again").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(ShareAction.OpenResult, ShareAction.AddAgain, ShareAction.Cancel), actions)
    }

    @Test
    fun `the interrupted note sits above Append`() {
        rule.setContent {
            Themed(dark = false) {
                ShareSheet(ShareUiState(text = SNIPPET, target = null, interrupted = true)) {}
            }
        }
        rule.onNodeWithText("The last Append didn't finish. Nothing was added.").assertIsDisplayed()
    }

    private companion object {
        const val SNIPPET = "A snippet from Chrome"
    }
}
