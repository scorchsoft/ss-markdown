package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorBanner
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.LineChange
import com.scorchsoft.ssmarkdown.editor.MenuItem
import com.scorchsoft.ssmarkdown.editor.SaveButton
import com.scorchsoft.ssmarkdown.editor.SaveProblem
import com.scorchsoft.ssmarkdown.editor.SaveStatusLine
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.fixtures.InterleavingDispatcher
import com.scorchsoft.ssmarkdown.navigator.FileRowKind
import com.scorchsoft.ssmarkdown.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The untitled-draft workflow end to end, over real directories: open the
 * app, type into a draft that never touches a file, then Save as to a
 * real one (ux-spec "Saving and safety"; quality-bar J1, J4). Every way
 * that can go (cancelled, failed, typed through, killed, crashed, New)
 * must keep every keystroke.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftWorkflowTest {
    @get:Rule val temp = TemporaryFolder()

    /** A fresh process over the same storage; [settings] are what the last one persisted. */
    private fun fixture(settings: AppSettings = AppSettings()) =
        EditorFixture(temp.root, UnconfinedTestDispatcher(), settings = settings)

    /** What MarkdownCodeView does: edit the buffer itself, then tell the listener which lines changed. */
    private fun ActiveDocument.typeAtEnd(typed: String) {
        val at = text.text.length
        val line = text.text.positionAt(at).line
        text.replace(at, 0, typed)
        onTextEdited(LineChange(line, 0, typed.count { it == '\n' }), text.version)
    }

    private fun EditorFixture.doc() = vm.active ?: error("nothing open")

    private fun EditorFixture.text() =
        doc()
            .text.text
            .toString()

    private fun EditorFixture.uri() = doc().open.uri

    /** The system picker creates an empty file and hands its URI back. */
    private fun EditorFixture.pickNewFile(name: String) = file(name, "")

    /** A fresh editor already showing an untitled draft with [typed] in it. */
    private fun draftWith(typed: String): EditorFixture {
        val f = fixture()
        f.vm.start()
        assertTrue(f.uri().isDraft)
        f.doc().typeAtEnd(typed)
        return f
    }

    @Test
    fun `Save as turns the draft into the file, and later saves go to the file`() {
        val f = draftWith("# Notes\nhello\n")
        val draft = f.uri()

        f.vm.onAction(EditorAction.SaveTapped)
        assertTrue(f.events.last() is EditorEvent.PickSaveLocation)
        f.vm.saves.onSaveLocation(f.pickNewFile("notes.md"))

        assertEquals("# Notes\nhello\n", f.disk("notes.md"))
        assertNull("the draft file is removed", f.drafts.resolve(draft)?.takeIf { it.exists() })
        assertTrue(f.drafts.list().none { it.uri == draft })
        assertEquals(DocumentUri(EditorFixture.SCHEME + "notes.md"), f.uri())
        val state = f.vm.uiState.value
        assertFalse(state.isDirty)
        assertEquals(SaveButton.AllSaved, state.save.button)
        assertNotEquals(SaveStatusLine.Draft, state.save.status)
        assertEquals("the title is the file's name, not the draft's", "notes.md", state.title)

        f.doc().typeAtEnd("more\n")
        assertEquals(SaveButton.Save, f.vm.uiState.value.save.button)
        f.vm.onAction(EditorAction.SaveTapped)

        assertEquals("# Notes\nhello\nmore\n", f.disk("notes.md"))
        assertFalse(f.vm.uiState.value.isDirty)
        assertTrue("no draft comes back", f.drafts.list().isEmpty())
    }

    @Test
    fun `the saved file becomes the one reopened on launch`() {
        val f = draftWith("# Notes\n")
        f.vm.saves.onSaveLocation(f.pickNewFile("notes.md"))
        f.vm.onPause()

        val after = fixture(f.settings.settings.value)
        after.vm.start()

        assertEquals(DocumentUri(EditorFixture.SCHEME + "notes.md"), after.uri())
        assertEquals("# Notes\n", after.text())
        assertTrue(after.drafts.list().isEmpty())
    }

    @Test
    fun `a cancelled Save as keeps the draft, its text and its place in the list`() {
        val f = draftWith("# Kept\nbody\n")
        val draft = f.uri()

        f.vm.onAction(EditorAction.SaveTapped)
        f.vm.saves.onSaveLocation(null)

        assertEquals(draft, f.uri())
        assertEquals("# Kept\nbody\n", f.text())
        assertEquals(SaveStatusLine.Draft, f.vm.uiState.value.save.status)
        assertNull(f.vm.uiState.value.banner)
        f.vm.onPause() // Leaving writes the draft (drafts always autosave).
        assertEquals("# Kept\nbody\n", f.drafts.resolve(draft)?.readText())
        f.vm.navigator.open()
        val rows =
            f.vm.uiState.value.navigator
                ?.drafts
                .orEmpty()
        assertEquals(listOf(draft.value), rows.map { it.uri })
        assertEquals("Kept", rows.single().name)
    }

    @Test
    fun `a failed Save as keeps the draft, says so, and loses nothing`() {
        val f = draftWith("# Precious\nwords\n")
        val draft = f.uri()
        val target = f.pickNewFile("full.md")
        f.failWrites = ProviderResult.StorageFull

        f.vm.saves.onSaveLocation(target)

        assertEquals(draft, f.uri())
        assertEquals("# Precious\nwords\n", f.text())
        assertEquals(EditorBanner.SaveFailed(SaveProblem.StorageFull), f.vm.uiState.value.banner)
        assertTrue(EditorEvent.ErrorTick in f.events)
        assertEquals("", f.disk("full.md"))
        assertTrue(f.drafts.list().any { it.uri == draft })
        f.vm.onPause()
        assertEquals("# Precious\nwords\n", f.drafts.resolve(draft)?.readText())

        f.failWrites = null
        f.vm.saves.onSaveLocation(target)
        assertEquals("# Precious\nwords\n", f.disk("full.md"))
        assertEquals(DocumentUri(target), f.uri())
        assertNull(f.vm.uiState.value.banner)
    }

    @Test
    fun `a Save as to a target that vanished never calls the draft missing`() {
        val f = draftWith("# Here\n")
        val draft = f.uri()

        f.vm.saves.onSaveLocation(EditorFixture.SCHEME + "gone.md")

        assertEquals(draft, f.uri())
        assertFalse("the draft is not missing", f.doc().open.fileMissing)
        assertEquals(EditorBanner.SaveFailed(SaveProblem.FileMissing), f.vm.uiState.value.banner)
    }

    @Test
    fun `typing while Save as waits on the disk is kept and journalled under the file`() {
        val scheduler = TestCoroutineScheduler()
        val io = InterleavingDispatcher()
        val f = EditorFixture(temp.root, StandardTestDispatcher(scheduler), io = io)
        f.vm.start()
        scheduler.advanceUntilIdle()
        val doc = f.doc()
        doc.typeAtEnd("# Fast\n")
        val target = f.pickNewFile("fast.md")

        io.afterEveryHop = { doc.typeAtEnd("+") }
        f.vm.saves.onSaveLocation(target)
        scheduler.advanceUntilIdle()
        io.afterEveryHop = {}
        doc.typeAtEnd("after")
        f.vm.onPause()

        val live = doc.text.text.toString()
        assertTrue(live, live.startsWith("# Fast\n+") && live.endsWith("after"))
        assertEquals(DocumentUri(target), f.uri())
        assertTrue("typed during the write: still unsaved", f.vm.uiState.value.isDirty)
        assertTrue(f.disk("fast.md").startsWith("# Fast\n"))

        val after = fixture()
        after.vm.openDocument(target)
        assertEquals(live, after.text())
    }

    @Test
    fun `a draft comes back after the process is killed`() {
        val f = draftWith("# Shopping\nmilk\n")
        val draft = f.uri()
        f.vm.onPause() // Backgrounded: the draft is written, then the process dies.

        val after = fixture(f.settings.settings.value)
        after.vm.start()

        assertEquals(draft, after.uri())
        assertEquals("# Shopping\nmilk\n", after.text())
        assertNull("nothing to recover: the draft itself was written", after.vm.uiState.value.banner)
        assertEquals(1, after.drafts.list().size)
    }

    @Test
    fun `a draft comes back from the journal after a crash mid-typing`() {
        val f = draftWith("# Crash\n")
        val draft = f.uri()
        f.doc().typeAtEnd("half a sent")
        // A crash: the journal reached the disk, the draft file never did (no onPause, no autosave).
        f.store.flushJournals()
        f.store.awaitJournals(JOURNAL_WAIT_MILLIS)
        assertEquals("", f.drafts.resolve(draft)?.readText())

        val after = fixture(f.settings.settings.value)
        after.vm.start()

        assertEquals(draft, after.uri())
        assertEquals("# Crash\nhalf a sent", after.text())
        val banner = after.vm.uiState.value.banner
        assertTrue("banner: $banner", banner is EditorBanner.Recovered)
        assertTrue("restored, not yet written to the draft", after.doc().open.isDirty)
        after.vm.onPause()
        assertEquals("# Crash\nhalf a sent", after.drafts.resolve(draft)?.readText())
    }

    @Test
    fun `New starts a fresh draft and keeps the one being typed in`() {
        val f = draftWith("# First\none\n")
        val first = f.uri()

        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.New))

        val second = f.uri()
        assertNotEquals("New must not reuse a draft that holds unsaved typing", first, second)
        assertTrue(second.isDraft)
        assertEquals("", f.text())
        f.doc().typeAtEnd("Second thoughts\n")
        f.vm.onPause()

        assertEquals("# First\none\n", f.drafts.resolve(first)?.readText())
        f.vm.navigator.open()
        val rows =
            f.vm.uiState.value.navigator
                ?.drafts
                .orEmpty()
        assertTrue(rows.all { it.kind == FileRowKind.Draft })
        assertEquals(setOf("First", "Second thoughts"), rows.map { it.name }.toSet())
    }

    @Test
    fun `New on an empty untouched draft reuses it rather than piling up empties`() {
        val f = fixture()
        f.vm.start()
        val first = f.uri()

        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.New))
        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.New))

        assertEquals(first, f.uri())
        assertEquals(1, f.drafts.list().size)

        f.doc().typeAtEnd("x")
        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.New))
        val second = f.uri()
        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.New))

        assertEquals(second, f.uri())
        assertEquals(2, f.drafts.list().size)
    }

    private companion object {
        const val JOURNAL_WAIT_MILLIS = 2_000L
    }
}
