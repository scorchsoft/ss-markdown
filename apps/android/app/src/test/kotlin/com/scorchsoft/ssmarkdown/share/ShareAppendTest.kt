package com.scorchsoft.ssmarkdown.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.scorchsoft.ssmarkdown.files.memory.HeapStats
import com.scorchsoft.ssmarkdown.files.memory.MemorySource
import com.scorchsoft.ssmarkdown.files.memory.SystemMemory
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.FileDocumentProvider
import com.scorchsoft.ssmarkdown.files.saf.SharedContent
import com.scorchsoft.ssmarkdown.files.saf.SharedStreamReader
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.HistoryListing
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.settings.RecentFile
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The share-in rows of docs/edge-cases.md ("Share-in and Navigator"),
 * through the real [ShareViewModel] over the editor's own store: the
 * append goes through the save pipeline, or into the open buffer, and a
 * target that cannot take it is refused with New note on offer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareAppendTest {
    @get:Rule val temp = TemporaryFolder()

    @get:Rule val rule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun EditorFixture.share(
        target: String,
        text: String = SNIPPET,
    ): ShareViewModel {
        settings.update { it.copy(recentFiles = listOf(RecentFile(target, target.removePrefix(SCHEME), "", 0))) }
        val share = ShareViewModel(store, drafts, history, settings, vm, scope) { "1 Nov 2026" }
        share.begin(text, "example.org")
        share.onAction(ShareAction.ChooseTarget(target))
        share.onAction(ShareAction.Confirm)
        return share
    }

    @Test
    fun `appending to a file with unsaved edits open in the editor goes into that buffer, never the file`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n")
        f.vm.openDocument(uri)
        val doc = f.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(0, "", "Draft. ")), TextSelection.caret(7))

        val share = f.share(uri)

        assertTrue(share.state.value.phase is SharePhase.Done)
        assertEquals("# Notes\n", f.disk("notes.md"))
        assertSame(doc.open, f.store[DocumentUri(uri)])
        assertEquals("Draft. # Notes\n\n$SNIPPET\n", doc.text.snapshot().toString())
        assertTrue("the appended buffer stays unsaved", doc.open.isDirty)
    }

    @Test
    fun `appending to a closed file changed by another app applies to the disk version and is in history`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n")
        f.vm.openDocument(uri)
        f.vm.openDocument(f.file("other.md", "# Other\n")) // notes.md is now closed, last seen as "# Notes".
        f.externalWrite("notes.md", "# Notes\n\nWritten on the laptop.\n")

        val share = f.share(uri)

        assertTrue(share.state.value.phase is SharePhase.Done)
        assertEquals("# Notes\n\nWritten on the laptop.\n\n$SNIPPET\n", f.disk("notes.md"))
        val listing = f.versions.list(DocumentKey(uri))
        val versions = (listing as? HistoryListing.Listed)?.versions.orEmpty()
        assertTrue("the append is a history entry", versions.any { it.kind == VersionKind.Appended })
    }

    @Test
    fun `appending to a clean open file changed by another app applies to the disk version, not the stale buffer`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n")
        f.vm.openDocument(uri)
        f.externalWrite("notes.md", "# Notes\n\nWritten on the laptop.\n")

        val share = f.share(uri)

        assertTrue(share.state.value.phase is SharePhase.Done)
        assertEquals("# Notes\n\nWritten on the laptop.\n\n$SNIPPET\n", f.disk("notes.md"))
        assertEquals(
            f.disk("notes.md"),
            f.vm.active
                ?.text
                ?.snapshot()
                .toString(),
        )
    }

    @Test
    fun `a read-only target is refused and New note is offered`() {
        val f = EditorFixture(temp.root, dispatcher)
        // A NUL byte opens read-only and is never written (robustness §4).
        val uri = f.file("binary.md", "# Binary\u0000\n")

        val share = f.share(uri)

        assertEquals(SharePhase.Blocked("binary.md", conflict = false), share.state.value.phase)
        assertEquals("# Binary\u0000\n", f.disk("binary.md"))
        share.onAction(ShareAction.ChooseTarget(null))
        assertEquals(SharePhase.Choosing, share.state.value.phase)
        assertEquals(null, share.state.value.target)
    }

    @Test
    fun `a conflicted target is refused and its file and buffer are untouched`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n")
        f.vm.openDocument(uri)
        val doc = f.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(0, "", "Mine. ")), TextSelection.caret(6))
        f.externalWrite("notes.md", "# Theirs\n")
        runBlocking {
            f.store.checkOutside(
                DocumentUri(uri),
                com.scorchsoft.ssmarkdown.files.document.CheckReason.Resume,
            )
        }
        assertTrue("the open buffer is in conflict", doc.open.conflict != null)

        val share = f.share(uri)

        assertEquals(SharePhase.Blocked("notes.md", conflict = true), share.state.value.phase)
        assertEquals("# Theirs\n", f.disk("notes.md"))
        assertEquals("Mine. # Notes\n", doc.text.snapshot().toString())
    }

    @Test
    fun `a missing target fails without creating it, and New note is offered`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("gone.md", "# Gone\n")
        File(f.files, "gone.md").delete()

        val share = f.share(uri)

        assertEquals(SharePhase.Failed("gone.md"), share.state.value.phase)
        assertTrue("nothing was recreated", !File(f.files, "gone.md").exists())
        share.onAction(ShareAction.ChooseTarget(null))
        assertEquals(SharePhase.Choosing, share.state.value.phase)
    }

    @Test
    fun `a target too large for this phone is declined by admission control before loading`() {
        val f = EditorFixture(temp.root, dispatcher, memory = TightMemory)
        val uri = f.file("huge.md", "x".repeat(HUGE_CHARS))

        val share = f.share(uri)

        assertEquals(SharePhase.Failed("huge.md"), share.state.value.phase)
        assertEquals(HUGE_CHARS.toLong(), File(f.files, "huge.md").length())
        assertEquals(null, f.store[DocumentUri(uri)])
    }

    @Test
    fun `a 5 MB share arrives as a stream, is size-checked, and appends in full`() {
        val f = EditorFixture(temp.root, dispatcher)
        val big = "line of shared text\n".repeat(FIVE_MB / LINE)
        File(f.files, "shared.txt").writeText(big)
        val reader = SharedStreamReader(FileDocumentProvider { File(f.files, it.value.removePrefix(SCHEME)) })
        val content = reader.read(DocumentUri("${SCHEME}shared.txt"))
        assertTrue(content is SharedContent.Text)
        val uri = f.file("notes.md", "# Notes\n")

        val share = f.share(uri, (content as SharedContent.Text).text)

        assertTrue(share.state.value.phase is SharePhase.Done)
        assertTrue(f.disk("notes.md").endsWith(big))

        File(f.files, "shared.txt").writeText("x".repeat(SharedStreamReader.DEFAULT_LIMIT_BYTES.toInt() + 1))
        assertTrue(reader.read(DocumentUri("${SCHEME}shared.txt")) is SharedContent.TooLarge)
    }

    @Test
    fun `the sheet offers New note when a named target failed, and Cancel when a new note did`() {
        var phase: SharePhase by mutableStateOf(SharePhase.Failed("gone.md"))
        val actions = ArrayList<ShareAction>()
        rule.setContent {
            Themed(
                dark = false,
            ) { ShareSheet(ShareUiState(text = SNIPPET, phase = phase)) { actions += it } }
        }
        rule.onNodeWithText("Couldn't add to gone.md. Make a new note instead?").assertIsDisplayed()
        rule.onNodeWithText("New note").performClick()
        assertEquals(listOf<ShareAction>(ShareAction.ChooseTarget(null)), actions)
        phase = SharePhase.Failed("")
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    /** A phone whose heap admits nothing bigger than a few hundred KB. */
    private object TightMemory : MemorySource {
        private const val MB = 1024L * 1024
        override val isLowRamDevice = true
        override val memoryClassMegabytes = 16

        override fun heap() = HeapStats(16 * MB, 15 * MB)

        override fun system() = SystemMemory(64 * MB, 32 * MB, lowMemory = true)

        override fun allocatableStorageBytes() = 4096 * MB
    }

    private companion object {
        const val SCHEME = EditorFixture.SCHEME
        const val SNIPPET = "A snippet from Chrome"
        const val HUGE_CHARS = 4 * 1024 * 1024
        const val FIVE_MB = 5 * 1024 * 1024
        const val LINE = 20
    }
}
