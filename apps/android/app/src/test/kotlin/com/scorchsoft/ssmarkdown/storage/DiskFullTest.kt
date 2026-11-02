package com.scorchsoft.ssmarkdown.storage

import com.scorchsoft.ssmarkdown.editor.EditorBanner
import com.scorchsoft.ssmarkdown.files.storage.StorageAccountant
import com.scorchsoft.ssmarkdown.files.storage.StorageLayout
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * edge-cases "Disk full for the journal or history": when the journal
 * cannot be written the editor says so at once ("Recovery copies have
 * stopped", with Save as) and keeps the text in memory, unsaved; and the
 * Storage screen warns while under 50 MB is free (`MemoryGovernor.storageLow`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiskFullTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun `a journal that cannot be written raises the banner and keeps the edit in memory`() {
        // Nothing can be created under "journal": every journal write fails, as on a full disk.
        File(temp.root, "journal").writeText("not a directory")
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("notes.md", "# Notes\n")
        f.vm.openDocument(uri)
        val doc = f.vm.active ?: error("nothing open")

        doc.surface.apply(listOf(EditOp(0, "", "Typed. ")), TextSelection.caret(7))
        f.store.flushJournals()

        assertEquals(EditorBanner.RecoveryCopiesStopped, f.vm.uiState.value.banner)
        assertEquals("Typed. # Notes\n", doc.text.snapshot().toString())
        assertTrue(doc.open.isDirty)
        assertEquals("# Notes\n", f.disk("notes.md"))
    }

    @Test
    fun `the Storage screen warns while storage is low`() {
        val f = EditorFixture(temp.root, dispatcher)
        val dirs = temp.newFolder()
        val vm =
            StorageViewModel(
                StorageDependencies(
                    versions = f.versions,
                    accountant = StorageAccountant(StorageLayout(dirs, dirs, dirs), dispatcher, { 0L }),
                    settings = f.settings,
                    store = f.store,
                    exportsDir = temp.newFolder("exports"),
                    cacheDir = temp.newFolder("cache"),
                    storageLow = { true },
                    clock = { 42L },
                    scope = f.scope,
                    io = dispatcher,
                ),
            )
        vm.load()
        assertTrue(vm.state.value.lowStorage)
    }
}
