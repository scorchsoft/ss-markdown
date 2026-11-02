package com.scorchsoft.ssmarkdown.folder

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorBanner
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.files.document.CheckReason
import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.saf.FolderRow
import com.scorchsoft.ssmarkdown.files.saf.TreeEntry
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Renaming the open file from the folder panel. A provider whose ids are
 * paths (on-device storage, SD cards) gives the renamed file a new URI, so
 * the editor was left holding the old one: the next check said "File no
 * longer exists", and unsaved edits could then only leave by Save as, or
 * as a recovered copy after a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenameOpenFileTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    /** A folder of `test:` files whose rename gives a new URI, as path-id providers do. */
    private class RenamingFolder(
        private val files: () -> File,
    ) : FolderSource {
        override fun list(
            treeUri: String,
            store: DocumentStore?,
            showAllFiles: Boolean,
        ): ProviderResult<List<FolderRow>> {
            // One row named after the folder, so a test can tell whose listing is shown.
            val name = treeUri.substringAfterLast('/') + ".md"
            val entry = TreeEntry(DocumentUri("$treeUri/$name"), name, name, false, 0, 0, "text/markdown")
            return ProviderResult.Ok(listOf(FolderRow(entry, 0, dirty = false, changedOutside = false)))
        }

        override fun rootOf(treeUri: String) = treeUri

        override fun create(
            parent: String,
            name: String,
        ) = ProviderResult.NotFound

        override fun rename(
            uri: String,
            name: String,
        ): ProviderResult<DocumentUri> {
            val from = File(files(), uri.removePrefix(EditorFixture.SCHEME))
            return if (from.renameTo(File(files(), name))) {
                ProviderResult.Ok(DocumentUri(EditorFixture.SCHEME + name))
            } else {
                ProviderResult.NotFound
            }
        }

        override fun duplicate(
            uri: String,
            parent: String,
            name: String,
        ) = ProviderResult.NotFound

        override fun delete(uri: String) = ProviderResult.NotFound
    }

    private fun fixture(
        io: CoroutineDispatcher = dispatcher,
        main: CoroutineDispatcher = dispatcher,
    ): EditorFixture {
        lateinit var f: EditorFixture
        f = EditorFixture(temp.root, main, io = io, folders = RenamingFolder { f.files })
        return f
    }

    private fun EditorFixture.activeUri() =
        vm.active
            ?.open
            ?.uri
            ?.value

    @Test
    fun `renaming the open file keeps it open under its new name`() {
        val f = fixture()
        val uri = f.file("old.md", "# Notes\n")
        f.vm.openDocument(uri)

        f.vm.onAction(EditorAction.Folder(FolderAction.Rename(uri, "new.md")))

        assertEquals(EditorFixture.SCHEME + "new.md", f.activeUri())
        assertEquals("# Notes\n", f.disk("new.md"))
        runBlocking { f.store.checkOutside(DocumentUri(f.activeUri()!!), CheckReason.Resume) }
        assertNotEquals(EditorBanner.FileMissing, f.vm.uiState.value.banner)
    }

    @Test
    fun `renaming the open file with unsaved edits asks for a save first and changes nothing`() {
        val f = fixture()
        val uri = f.file("old.md", "# Notes\n")
        f.vm.openDocument(uri)
        f.vm.active!!
            .surface
            .apply(listOf(EditOp(0, "", "Typed. ")), TextSelection.caret(7))

        f.vm.onAction(EditorAction.Folder(FolderAction.Rename(uri, "new.md")))

        assertTrue(File(f.files, "old.md").exists())
        assertEquals(uri, f.activeUri())
        assertTrue(f.vm.uiState.value.isDirty)
        assertEquals(
            MessageKind.SaveBeforeRename,
            f.vm.uiState.value.message
                ?.kind,
        )
    }

    @Test
    fun `a folder listing that lands after another folder was opened is not shown for it`() {
        // Disk work waits until the test runs it, in the order the test chooses.
        val queued = ArrayList<Runnable>()
        val io =
            object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) {
                    queued += block
                }
            }
        val f = fixture(io)
        f.vm.folder.onFolderPicked(FIRST)
        // The first folder's listing is slow: the user opens another folder, which lists at once.
        f.vm.folder.onFolderPicked(SECOND)
        // Each pick queued its listing, then a picture refresh: the second folder's work runs first.
        val work = queued.toList()
        queued.clear()
        assertEquals(4, work.size)
        listOf(2, 3, 0, 1).forEach { work[it].run() }

        val folder = f.vm.uiState.value.folder!!
        assertEquals(SECOND, folder.treeUri)
        assertEquals(listOf("second.md"), folder.rows.map { it.name })
    }

    private companion object {
        const val FIRST = "content://tree/first"
        const val SECOND = "content://tree/second"
    }
}
