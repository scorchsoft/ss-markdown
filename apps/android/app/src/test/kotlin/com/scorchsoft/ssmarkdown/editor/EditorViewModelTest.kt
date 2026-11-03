package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.find.FindAction
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.HistoryListing
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.ui.strip.StripKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The editor's state machines end to end over real files: save, the
 * conflict flow, recovery after a restart, leaving with unsaved edits,
 * replace all and the strip. No view is attached, so edits go through
 * the buffer surface exactly as Preview mode's would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun fixture() = EditorFixture(temp.root, dispatcher)

    private fun EditorFixture.text() =
        vm.active
            ?.text
            ?.text
            ?.toString()

    private fun EditorFixture.type(
        at: Int,
        text: String,
    ) {
        val doc = vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(at, "", text)), TextSelection.caret(at + text.length))
    }

    private fun EditorFixture.openFile(
        name: String,
        text: String,
    ) {
        vm.openDocument(file(name, text))
    }

    @Test
    fun `first launch opens an untitled draft, already safe`() {
        val f = fixture()
        f.vm.start()
        assertTrue(
            f.vm.active
                ?.open
                ?.uri
                ?.isDraft == true,
        )
        assertEquals(SaveButton.SaveAsDraft, f.vm.uiState.value.save.button)
        assertEquals(SaveStatusLine.Draft, f.vm.uiState.value.save.status)
    }

    @Test
    fun `editing turns Save red and Save writes in place`() {
        val f = fixture()
        f.openFile("a.md", "# A\n")
        f.type(4, "more\n")
        assertEquals(SaveButton.Save, f.vm.uiState.value.save.button)
        assertTrue(f.vm.uiState.value.isDirty)
        f.vm.onAction(EditorAction.SaveTapped)
        assertEquals("# A\nmore\n", f.disk("a.md"))
        assertEquals(SaveButton.AllSaved, f.vm.uiState.value.save.button)
        assertEquals(
            MessageKind.SavedTo,
            f.vm.uiState.value.message
                ?.kind,
        )
        assertTrue(EditorEvent.Tick in f.events)
    }

    @Test
    fun `a draft's Save asks for a place, named from the first heading`() {
        val f = fixture()
        f.vm.start()
        f.type(0, "# Release notes\n")
        f.vm.onAction(EditorAction.SaveTapped)
        assertEquals(EditorEvent.PickSaveLocation("Release notes.md"), f.events.last())
    }

    @Test
    fun `an outside change while unsaved opens the sheet and keeping theirs is clean`() {
        val f = fixture()
        f.openFile("r.md", "one\ntwo\n")
        f.type(0, "zero\n")
        f.externalWrite("r.md", "one\ntwo\nthree\n")
        f.vm.onAction(EditorAction.SaveTapped)
        assertEquals("one\ntwo\nthree\n", f.disk("r.md"))
        val sheet = f.vm.uiState.value.overlay as? EditorOverlay.Conflict
        assertNotNull(sheet)
        assertEquals(EditorBanner.ChangedOutside, f.vm.uiState.value.banner)
        f.vm.onAction(EditorAction.KeepTheirs)
        assertEquals("one\ntwo\nthree\n", f.text())
        assertFalse(f.vm.uiState.value.isDirty)
        assertNull(f.vm.uiState.value.banner)
    }

    @Test
    fun `merge my edits keeps both sides when nothing overlaps`() {
        val f = fixture()
        f.openFile("m.md", "a\nb\nc\nd\ne\n")
        f.type(0, "A\n")
        f.externalWrite("m.md", "a\nb\nc\nd\ne\nf\n")
        f.vm.onAction(EditorAction.SaveTapped)
        val summary = (f.vm.uiState.value.overlay as EditorOverlay.Conflict).summary
        assertEquals(0, summary.overlaps)
        assertTrue(summary.canMerge)
        f.vm.onAction(EditorAction.MergeMine)
        assertEquals("A\na\nb\nc\nd\ne\nf\n", f.text())
        assertTrue(f.vm.uiState.value.isDirty)
    }

    @Test
    fun `a merge settled for one file is never applied to another`() {
        val f = fixture()
        // Two files in conflict, each with one edit of mine, so their buffers stand at the same version.
        f.openFile("one.md", "a\nb\n")
        f.type(0, "mine\n")
        f.externalWrite("one.md", "theirs\nb\n")
        f.vm.onAction(EditorAction.SaveTapped)
        f.openFile("two.md", "x\ny\n")
        f.type(0, "mine too\n")
        f.externalWrite("two.md", "theirs too\ny\n")
        f.vm.onAction(EditorAction.SaveTapped)
        f.vm.openDocument("test:one.md") // Folder-style switch: the dirty one.md was kept in memory.
        f.vm.onAction(EditorAction.MergeMine) // An overlap: MergeResolve opens for one.md.
        assertNotNull(f.vm.conflicts.merge.value)

        f.vm.openDocument("test:two.md") // Meanwhile two.md is on screen.
        f.vm.conflicts.finishMerge()

        assertEquals("mine too\nx\ny\n", f.text())
        assertNotNull(
            f.store[
                com.scorchsoft.ssmarkdown.files.provider
                    .DocumentUri("test:two.md"),
            ]?.conflict,
        )
    }

    @Test
    fun `use my version asks first`() {
        val f = fixture()
        f.openFile("u.md", "x\n")
        f.type(0, "mine ")
        f.externalWrite("u.md", "theirs\n")
        f.vm.onAction(EditorAction.SaveTapped)
        f.vm.onAction(EditorAction.AskUseMine)
        assertEquals(EditorOverlay.ConfirmUseMine, f.vm.uiState.value.overlay)
        f.vm.onAction(EditorAction.ConfirmUseMine)
        f.vm.onAction(EditorAction.SaveTapped)
        assertEquals("mine x\n", f.disk("u.md"))
    }

    @Test
    fun `unsaved edits come back after a restart, and discard can be undone`() {
        val first = fixture()
        first.openFile("k.md", "kept\n")
        first.type(0, "typed ")
        first.vm.onPause()
        val second = EditorFixture(temp.root, dispatcher)
        second.vm.start()
        assertEquals("typed kept\n", second.text())
        assertTrue(second.vm.uiState.value.banner is EditorBanner.Recovered)
        assertEquals("kept\n", second.disk("k.md"))
        second.vm.onAction(EditorAction.RecoveryDiscard)
        assertEquals("kept\n", second.text())
        assertTrue(
            second.vm.uiState.value.message
                ?.undo == true,
        )
        second.vm.onAction(EditorAction.MessageUndo)
        assertEquals("typed kept\n", second.text())
        assertTrue(second.vm.uiState.value.isDirty)
    }

    @Test
    fun `Undo on a discard never writes the discarded text into another document`() {
        val first = fixture()
        first.openFile("k.md", "kept\n")
        first.type(0, "typed ")
        first.vm.onPause()
        val second = EditorFixture(temp.root, dispatcher)
        second.vm.start()
        second.vm.onAction(EditorAction.RecoveryDiscard)
        // The message with Undo is still up when the user opens another file.
        second.openFile("other.md", "other\n")
        second.vm.onAction(EditorAction.MessageUndo)

        assertEquals("other\n", second.text())
        assertFalse(second.vm.uiState.value.isDirty)
    }

    @Test
    fun `back on a dirty file asks, and Don't save leaves a History copy`() {
        val f = fixture()
        f.openFile("b.md", "base\n")
        f.type(0, "lost? ")
        f.vm.onAction(EditorAction.BackPressed)
        assertEquals(EditorOverlay.DirtyPrompt(PendingLeave.Exit), f.vm.uiState.value.overlay)
        f.vm.onAction(EditorAction.LeaveDiscard)
        assertEquals(EditorEvent.Finish, f.events.last())
        val listing = f.versions.list(DocumentKey(EditorFixture.SCHEME + "b.md")) as HistoryListing.Listed
        assertTrue(listing.versions.any { it.kind == VersionKind.Checkpoint })
        assertEquals("base\n", f.disk("b.md"))
    }

    @Test
    fun `replace all is one undo step with a checkpoint first`() {
        val f = fixture()
        f.openFile("f.md", "a a a\n")
        f.vm.onAction(EditorAction.Find(FindAction.Open))
        f.vm.onAction(EditorAction.Find(FindAction.Query("a")))
        assertEquals(
            3,
            f.vm.uiState.value.find
                ?.matches
                ?.size,
        )
        f.vm.onAction(EditorAction.Find(FindAction.Replacement("b")))
        f.vm.onAction(EditorAction.Find(FindAction.ReplaceAll))
        assertEquals("b b b\n", f.text())
        f.vm.active
            ?.surface
            ?.undo()
        assertEquals("a a a\n", f.text())
    }

    @Test
    fun `strip keys run edit commands`() {
        val f = fixture()
        f.openFile("s.md", "word\n")
        f.vm.active
            ?.surface
            ?.select(0, 4)
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Bold))
        assertEquals("**word**\n", f.text())
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Undo))
        assertEquals("word\n", f.text())
    }

    @Test
    fun `the save menu toggles autosave for this file only`() {
        val f = fixture()
        f.openFile("t.md", "t\n")
        f.vm.onAction(EditorAction.SaveLongPressed)
        assertEquals(EditorOverlay.SaveMenu(autosaveThisFile = false), f.vm.uiState.value.overlay)
        f.vm.onAction(EditorAction.ToggleAutosaveThisFile)
        assertEquals(mapOf(EditorFixture.SCHEME + "t.md" to true), f.settings.settings.value.autosaveOverrides)
    }
}
