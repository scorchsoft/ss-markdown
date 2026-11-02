package com.scorchsoft.ssmarkdown.storage

import com.scorchsoft.ssmarkdown.files.document.SET_ASIDE_CAP_BYTES
import com.scorchsoft.ssmarkdown.files.document.SetAsideEdits
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.storage.StorageAccountant
import com.scorchsoft.ssmarkdown.files.storage.StorageLayout
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.history.journal.Cancellable
import com.scorchsoft.ssmarkdown.history.journal.FlushScheduler
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import com.scorchsoft.ssmarkdown.nav.ScreenEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Set-aside edits (the journal's quarantine) leave only by the user's
 * hand: Storage → Clear set-aside edits → "This can't be undone", with
 * the export ticked; the export is verified before anything is deleted,
 * and a failed export deletes nothing (edge-cases, quarantine retention).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SetAsideClearViewModelTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val events = ArrayList<ScreenEvent>()

    /** One quarantined journal, made the way the app makes them: typed, flushed, set aside. */
    private fun fixtureWithSetAsideEdits(): EditorFixture {
        val first = EditorFixture(temp.root, dispatcher)
        val uri = first.file("lost.md", "# Lost\n")
        first.vm.openDocument(uri)
        val doc = first.vm.active ?: error("not open")
        doc.open.replace(doc.text.text.length, 0, "\nwords worth keeping")
        first.store.flushJournals()
        JournalStore(
            File(temp.root, "journal"),
            FlushScheduler {
                _,
                _,
                ->
                Cancellable { }
            },
        ).setAside(DocumentUri(uri).value)
        return EditorFixture(temp.root, dispatcher)
    }

    private fun viewModel(
        fixture: EditorFixture,
        exportsDir: File,
    ): StorageViewModel {
        val dirs = temp.newFolder()
        val vm =
            StorageViewModel(
                StorageDependencies(
                    versions = fixture.versions,
                    accountant = StorageAccountant(StorageLayout(dirs, dirs, dirs), dispatcher, { 0L }),
                    settings = fixture.settings,
                    store = fixture.store,
                    exportsDir = exportsDir,
                    cacheDir = temp.newFolder(),
                    storageLow = { false },
                    clock = { 42L },
                    scope = fixture.scope,
                    io = dispatcher,
                ),
            )
        fixture.scope.launch { vm.events.collect { events += it } }
        vm.load()
        return vm
    }

    private fun quarantine(): List<File> = File(temp.root, "journal/quarantine").listFiles().orEmpty().toList()

    @Test
    fun `the clear asks first, exports and verifies, then deletes exactly what it listed`() {
        val fixture = fixtureWithSetAsideEdits()
        val vm = viewModel(fixture, temp.newFolder("exports"))
        assertEquals(1, vm.state.value.setAside.size)
        assertEquals(1, quarantine().size)

        vm.onAction(StorageAction.AskClear(ClearKind.SetAside))
        val confirm = vm.state.value.confirm
        assertEquals(ClearKind.SetAside, confirm?.kind)
        assertTrue("export is ticked by default", confirm?.exportFirst == true)
        assertEquals(1, quarantine().size) // Asking deletes nothing.

        vm.onAction(StorageAction.ConfirmClear)

        assertTrue(quarantine().isEmpty())
        val zip = events.filterIsInstance<ScreenEvent.SaveZip>().single()
        assertTrue(zip.file.length() > 0)
        assertEquals(StorageMessage.SetAsideCleared(1, confirm!!.bytes), vm.state.value.message)
    }

    @Test
    fun `a failed export deletes nothing`() {
        val fixture = fixtureWithSetAsideEdits()
        val notADirectory = temp.newFile("exports-is-a-file")
        val vm = viewModel(fixture, notADirectory)

        vm.onAction(StorageAction.AskClear(ClearKind.SetAside))
        vm.onAction(StorageAction.ConfirmClear)

        assertEquals(1, quarantine().size)
        assertEquals(StorageMessage.Failed, vm.state.value.message)
        assertTrue(events.none { it is ScreenEvent.SaveZip })
    }

    @Test
    fun `cancelling deletes nothing, and loading never does either`() {
        val fixture = fixtureWithSetAsideEdits()
        val vm = viewModel(fixture, temp.newFolder("exports"))
        vm.onAction(StorageAction.AskClear(ClearKind.SetAside))
        vm.onAction(StorageAction.CancelConfirm)
        vm.load()
        assertEquals(1, quarantine().size)
        assertFalse(vm.state.value.setAsideOverCap)
    }

    @Test
    fun `over the cap the screen says so, and still deletes nothing`() {
        val big = SetAsideEdits(null, null, 0, SET_ASIDE_CAP_BYTES + 1, File("x"))
        val state = StorageUiState(setAside = listOf(big))
        assertTrue(state.setAsideOverCap)
        val confirm = StorageReducer.confirmForSetAside(state.setAside)
        assertTrue(confirm.exportFirst)
        assertEquals(null, confirm.request)
    }
}
