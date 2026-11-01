package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.CopyReason
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorBanner
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.LineChange
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.editor.NotKeptStep
import com.scorchsoft.ssmarkdown.editor.PendingLeave
import com.scorchsoft.ssmarkdown.editor.SaveProblem
import com.scorchsoft.ssmarkdown.files.document.CheckpointResult
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * No path destroys or strands text while the UI says it is kept (data
 * safety review round 2: R2-DS-1…DS-4), end to end through the editor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NothingStrandedTest {
    @get:Rule val temp = TemporaryFolder()

    private fun ActiveDocument.typeAtEnd(typed: String) {
        val at = text.text.length
        val line = text.text.positionAt(at).line
        text.replace(at, 0, typed)
        onTextEdited(LineChange(line, 0, typed.count { it == '\n' }), text.version)
    }

    private fun EditorFixture.text(): String =
        vm.active
            ?.text
            ?.text
            ?.toString()
            .orEmpty()

    /** History's disk goes away (full, damaged): every record from now on fails. */
    private fun EditorFixture.breakHistory() {
        val dir = File(root, "history")
        dir.deleteRecursively()
        dir.writeText("not a directory")
    }

    /** A first session typed into [name] and was killed after `onPause`. */
    private fun typedAndKilled(
        name: String,
        typed: String,
    ): String {
        val first = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val uri = first.file(name, "base\n")
        first.vm.openDocument(uri)
        first.vm.active?.typeAtEnd(typed)
        first.vm.onPause()
        return uri
    }

    @Test
    fun `R2-DS-1 Don't save asks when History could not keep a copy, and keeps the document until told`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val uri = f.file("a.md", "base\n")
        f.vm.openDocument(uri)
        val open = f.vm.active?.open ?: error("nothing open")
        f.vm.active?.typeAtEnd("precious")
        f.breakHistory()

        f.vm.saves.leave(PendingLeave.NewDocument, discard = true)

        assertEquals(
            EditorOverlay.NotKept(NotKeptStep.DontSave(PendingLeave.NewDocument), CheckpointResult.Failed),
            f.vm.uiState.value.overlay,
        )
        assertTrue(f.store[DocumentUri(uri)] === open)
        assertEquals("base\nprecious", f.text())

        f.vm.onAction(EditorAction.ConfirmNotKept)

        assertTrue(f.store[DocumentUri(uri)] == null)
        assertTrue(
            f.vm.active
                ?.open
                ?.uri
                ?.isDraft == true,
        )
    }

    @Test
    fun `R2-DS-1 Discard edits asks when History could not keep them, then says so, with Undo`() {
        val uri = typedAndKilled("b.md", "precious")
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.start()
        assertTrue(f.vm.uiState.value.banner is EditorBanner.Recovered)
        f.breakHistory()

        f.vm.onAction(EditorAction.RecoveryDiscard)

        assertEquals(
            EditorOverlay.NotKept(NotKeptStep.DiscardRecovered, CheckpointResult.Failed),
            f.vm.uiState.value.overlay,
        )
        assertEquals("base\nprecious", f.text())
        assertTrue("the banner comes back", f.vm.uiState.value.banner is EditorBanner.Recovered)

        f.vm.onAction(EditorAction.ConfirmNotKept)

        assertEquals("base\n", f.text())
        val message = f.vm.uiState.value.message ?: error("no message")
        assertEquals(MessageKind.EditsDiscardedNotKept, message.kind)
        assertTrue(message.undo)
        assertEquals(
            DocumentUri(uri),
            f.vm.active
                ?.open
                ?.uri,
        )
    }

    @Test
    fun `R2-DS-1 Keep the file's version asks when History could not keep mine`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val uri = f.file("c.md", "base\n")
        f.vm.openDocument(uri)
        f.vm.active?.typeAtEnd("mine")
        f.externalWrite("c.md", "theirs\n")
        f.vm.onResume()
        assertNotNull(
            f.vm.active
                ?.open
                ?.conflict,
        )
        f.breakHistory()

        f.vm.onAction(EditorAction.KeepTheirs)

        assertEquals(
            EditorOverlay.NotKept(NotKeptStep.KeepTheirs, CheckpointResult.Failed),
            f.vm.uiState.value.overlay,
        )
        assertEquals("base\nmine", f.text())
        assertNotNull(
            f.vm.active
                ?.open
                ?.conflict,
        )
    }

    @Test
    fun `R2-DS-2 edits to a file that is gone after a restart open as a recovered copy`() {
        typedAndKilled("d.md", "precious")
        File(temp.root, "files/d.md").delete()
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())

        f.vm.start()

        val banner = f.vm.uiState.value.banner
        assertTrue(banner.toString(), banner is EditorBanner.RecoveredCopy)
        banner as EditorBanner.RecoveredCopy
        assertEquals(CopyReason.Unreachable, banner.reason)
        assertTrue(banner.complete)
        assertEquals("base\nprecious", f.text())
        assertTrue(
            f.vm.active
                ?.open
                ?.uri
                ?.isDraft == true,
        )
        assertTrue("never the generic error", f.vm.uiState.value.message == null)
    }

    @Test
    fun `R2-DS-3 kept edits open as a copy from their banner`() {
        val uri = typedAndKilled("e.md", "precious")
        File(temp.root, "base").deleteRecursively()
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.externalWrite("e.md", "theirs\n")
        f.vm.start()
        assertEquals(EditorBanner.EditsKeptAside, f.vm.uiState.value.banner)

        f.vm.onAction(EditorAction.OpenKeptAsCopy)

        val banner = f.vm.uiState.value.banner as EditorBanner.RecoveredCopy
        assertEquals(CopyReason.NotApplied, banner.reason)
        assertEquals("e.md", banner.source)
        assertEquals("precious", f.text())
        assertEquals("theirs\n", f.disk("e.md"))
        assertEquals("set aside, not deleted", 1, File(temp.root, "journal/quarantine").list()?.size)
        assertTrue("no launch offers them again", f.store[DocumentUri(uri)]?.holdsUnappliedEdits != true)
    }

    @Test
    fun `R2-DS-4 Save as onto a file open with unsaved edits is refused and says why`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val a = f.file("f.md", "A\n")
        val b = f.file("g.md", "B\n")
        f.vm.openDocument(a)
        f.vm.active?.typeAtEnd("unsaved in A")
        f.vm.openDocument(b) // A stays in memory, dirty.
        f.vm.active?.typeAtEnd("more B")

        f.vm.saves.onSaveLocation(a)

        assertEquals(EditorBanner.SaveFailed(SaveProblem.TargetOpen), f.vm.uiState.value.banner)
        assertEquals("A\n", f.disk("f.md"))
        assertTrue(f.store[DocumentUri(a)]?.isDirty == true)
    }
}
