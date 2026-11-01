package com.scorchsoft.ssmarkdown.fixtures

import com.scorchsoft.ssmarkdown.document.DiskReader
import com.scorchsoft.ssmarkdown.document.VersionHistoryHook
import com.scorchsoft.ssmarkdown.editor.EditorDependencies
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.files.document.AutosaveScheduler
import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.drafts.DraftStore
import com.scorchsoft.ssmarkdown.files.memory.HeapStats
import com.scorchsoft.ssmarkdown.files.memory.MemoryGovernor
import com.scorchsoft.ssmarkdown.files.memory.MemorySource
import com.scorchsoft.ssmarkdown.files.memory.SystemMemory
import com.scorchsoft.ssmarkdown.files.provider.FileDocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.RoutingDocumentProvider
import com.scorchsoft.ssmarkdown.files.saf.FolderRow
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore
import com.scorchsoft.ssmarkdown.folder.FolderSource
import com.scorchsoft.ssmarkdown.history.journal.Cancellable
import com.scorchsoft.ssmarkdown.history.journal.FlushScheduler
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import com.scorchsoft.ssmarkdown.history.versions.VersionStore
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.InMemorySettingsRepository
import com.scorchsoft.ssmarkdown.viewstate.InMemoryViewStates
import com.scorchsoft.ssmarkdown.viewstate.ViewStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * A whole editor over real directories under [root]: the store, journal,
 * history and drafts, with files addressed as `test:<name>` in
 * `root/files`. Building a second fixture over the same [root] is a
 * process restart: whatever was flushed survives, nothing else does.
 */
@Suppress("LongParameterList") // A test fixture: every collaborator a test may swap.
class EditorFixture(
    val root: File,
    dispatcher: CoroutineDispatcher,
    settings: AppSettings = AppSettings(),
    var now: Long = 1_000_000L,
    viewStates: ViewStateStore = InMemoryViewStates(),
    /** Where disk work runs; an [InterleavingDispatcher] lets a test type while it waits. */
    io: CoroutineDispatcher = dispatcher,
    /** Where find searches run; the test dispatcher by default, so a search lands before the next line. */
    compute: CoroutineDispatcher = dispatcher,
) {
    val files = File(root, "files").apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val drafts = DraftStore(File(root, "drafts"), { now })
    private val provider =
        RoutingDocumentProvider(
            drafts = FileDocumentProvider(drafts::resolve),
            documents =
                FileDocumentProvider { uri ->
                    if (uri.value.startsWith(SCHEME)) File(files, uri.value.removePrefix(SCHEME)) else null
                },
        )
    private val journals = JournalStore(File(root, "journal"), FlushScheduler { _, _ -> Cancellable { } })
    val versions = VersionStore(File(root, "history"))
    val history = VersionHistoryHook(versions, scope)
    val store =
        DocumentStore(
            provider,
            journals,
            BaseCopyStore(File(root, "base")),
            MemoryGovernor(RoomyMemory),
            io,
            history,
        )
    val settings = InMemorySettingsRepository(settings)
    val autosave =
        AutosaveScheduler(scope, store, {
            this.settings.settings.value
                .autosaves(it.value, it.isDraft)
        })
    val events = ArrayList<EditorEvent>()
    val vm =
        EditorViewModel(
            EditorDependencies(
                store = store,
                autosave = autosave,
                drafts = drafts,
                history = history,
                versions = versions,
                settings = this.settings,
                diskReader = DiskReader(provider, io),
                folders = NoFolders,
                permissions = { _, _ -> true },
                clock = { now },
                scope = scope,
                io = io,
                compute = compute,
                viewStates = viewStates,
            ),
        ).also { vm ->
            autosave.start()
            scope.launch { vm.events.collect { events += it } }
        }

    /** Writes [text] to `files/<name>` and returns its URI. */
    fun file(
        name: String,
        text: String,
    ): String {
        File(files, name).writeText(text)
        return SCHEME + name
    }

    /** What another app does: rewrites the file behind our back. */
    fun externalWrite(
        name: String,
        text: String,
    ) {
        val file = File(files, name)
        file.writeText(text)
        file.setLastModified(file.lastModified() + 5_000)
    }

    /** The file's bytes on disk, as text. */
    fun disk(name: String): String = File(files, name).readText()

    companion object {
        const val SCHEME = "test:"
    }
}

/** A phone with plenty of memory and storage. */
private object RoomyMemory : MemorySource {
    private const val MB = 1024L * 1024
    override val isLowRamDevice = false
    override val memoryClassMegabytes = 512

    override fun heap() = HeapStats(512 * MB, 40 * MB)

    override fun system() = SystemMemory(2048 * MB, 128 * MB, lowMemory = false)

    override fun allocatableStorageBytes() = 4096 * MB
}

/** No folder mode in these tests. */
private object NoFolders : FolderSource {
    override fun list(
        treeUri: String,
        store: DocumentStore?,
        showAllFiles: Boolean,
    ): ProviderResult<List<FolderRow>> = ProviderResult.NotFound

    override fun rootOf(treeUri: String) = treeUri

    override fun create(
        parent: String,
        name: String,
    ) = ProviderResult.NotFound

    override fun rename(
        uri: String,
        name: String,
    ) = ProviderResult.NotFound

    override fun duplicate(
        uri: String,
        parent: String,
        name: String,
    ) = ProviderResult.NotFound

    override fun delete(uri: String) = ProviderResult.NotFound
}
