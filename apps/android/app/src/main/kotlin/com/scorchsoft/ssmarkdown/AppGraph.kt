package com.scorchsoft.ssmarkdown

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import com.scorchsoft.ssmarkdown.document.DiskReader
import com.scorchsoft.ssmarkdown.document.VersionHistoryHook
import com.scorchsoft.ssmarkdown.editor.EditorDependencies
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.files.document.AutosaveScheduler
import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.drafts.DraftStore
import com.scorchsoft.ssmarkdown.files.memory.AndroidMemorySource
import com.scorchsoft.ssmarkdown.files.memory.MemoryGovernor
import com.scorchsoft.ssmarkdown.files.provider.ContentResolverProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.FileDocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.RoutingDocumentProvider
import com.scorchsoft.ssmarkdown.files.saf.ContentResolverTreeSource
import com.scorchsoft.ssmarkdown.files.saf.DocumentActions
import com.scorchsoft.ssmarkdown.files.saf.FolderLister
import com.scorchsoft.ssmarkdown.files.saf.PersistedPermissions
import com.scorchsoft.ssmarkdown.files.saf.SharedStreamReader
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore
import com.scorchsoft.ssmarkdown.files.storage.StorageAccountant
import com.scorchsoft.ssmarkdown.files.storage.StorageLayout
import com.scorchsoft.ssmarkdown.folder.SafFolderSource
import com.scorchsoft.ssmarkdown.history.HistoryViewModel
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import com.scorchsoft.ssmarkdown.history.versions.VersionStore
import com.scorchsoft.ssmarkdown.nav.ScreenStack
import com.scorchsoft.ssmarkdown.platform.ExitReasons
import com.scorchsoft.ssmarkdown.platform.HandlerFlushScheduler
import com.scorchsoft.ssmarkdown.settings.PersistedSettingsRepository
import com.scorchsoft.ssmarkdown.settings.PreferencesSettingsStore
import com.scorchsoft.ssmarkdown.settings.SettingsRepository
import com.scorchsoft.ssmarkdown.storage.StorageDependencies
import com.scorchsoft.ssmarkdown.storage.StorageViewModel
import com.scorchsoft.ssmarkdown.viewstate.JsonViewStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Builds everything, once per process (docs/engineering.md: constructor
 * injection, no DI framework, no singleton but this). It is created by
 * [SSMarkdownApplication] and outlives every Activity, which is what lets
 * the [DocumentStore] own the text across rotation, folding and window
 * changes (robustness §5a, rule 1).
 *
 * Construction does no I/O beyond reading settings; every store opens
 * its files lazily, so nothing on the launch path can throw.
 */
class AppGraph(
    context: Context,
) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** Wall time, injected everywhere a test needs to freeze it. */
    val clock: () -> Long = System::currentTimeMillis

    /** Every byte of I/O runs here. */
    val io = Dispatchers.IO

    /** Background work that must finish even if the screen goes (history records, measurements). */
    val ioScope = CoroutineScope(SupervisorJob() + io)

    /** The store and autosave are owned by the main thread. */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Where each kind of app data lives (ux-spec "Explaining Android's buttons"). */
    val layout = StorageLayout(app.filesDir, app.noBackupFilesDir, app.cacheDir)

    val settings: SettingsRepository =
        PersistedSettingsRepository(
            PreferencesSettingsStore(app.getSharedPreferences("settings", Context.MODE_PRIVATE)),
        )

    val versions = VersionStore(layout.history, policy = settings.settings.value.history)

    val historyHook = VersionHistoryHook(versions, ioScope)

    val drafts = DraftStore(layout.drafts, clock)

    val provider =
        RoutingDocumentProvider(
            drafts = FileDocumentProvider(drafts::resolve),
            documents = ContentResolverProvider(resolver),
        )

    private val journals = JournalStore(layout.journals, HandlerFlushScheduler(Handler(Looper.getMainLooper())))

    val governor =
        MemoryGovernor(
            AndroidMemorySource(
                app.getSystemService(ActivityManager::class.java),
                app.getSystemService(StorageManager::class.java),
                app.filesDir,
            ),
        )

    val store = DocumentStore(provider, journals, BaseCopyStore(layout.baseCopies), governor, io, historyHook)

    val autosave =
        AutosaveScheduler(mainScope, store, isEnabled = { uri: DocumentUri ->
            settings.settings.value.autosaves(uri.value, uri.isDraft)
        })

    val accountant = StorageAccountant(layout, io, clock, appBytes = { File(app.applicationInfo.sourceDir).length() })

    val permissions = PersistedPermissions(resolver)

    val folderSource =
        SafFolderSource(FolderLister(ContentResolverTreeSource(resolver)), DocumentActions(resolver, provider))

    val sharedReader = SharedStreamReader(provider)

    val diskReader = DiskReader(provider, io)

    val exitReasons = ExitReasons(app)

    /** The screen back stack; process-scoped so a configuration change keeps it. */
    val screens = ScreenStack()

    /** Where history exports wait until the user picks a place for them. */
    val exportsDir = File(app.filesDir, "exports")

    /** The Storage screen's view model. */
    val storage: StorageViewModel by lazy {
        StorageViewModel(
            StorageDependencies(
                versions = versions,
                accountant = accountant,
                settings = settings,
                store = store,
                exportsDir = exportsDir,
                cacheDir = layout.previewCache,
                storageLow = governor::storageLow,
                clock = clock,
                scope = mainScope,
                io = io,
            ),
        )
    }

    /** The History screen's view model; it shows the editor's active document. */
    val history: HistoryViewModel by lazy { HistoryViewModel(versions, editor, io, exportsDir) }

    /** The editor's view model. Process-scoped like the store, so no rotation or window change can drop it. */
    val editor: EditorViewModel by lazy {
        EditorViewModel(
            EditorDependencies(
                store = store,
                autosave = autosave,
                drafts = drafts,
                history = historyHook,
                versions = versions,
                settings = settings,
                diskReader = diskReader,
                folders = folderSource,
                permissions = { uri, write -> permissions.take(DocumentUri(uri), write) },
                clock = clock,
                lastExitWasSwipe = exitReasons::lastExitWasUserSwipe,
                scope = mainScope,
                io = io,
                viewStates = JsonViewStateStore(File(app.filesDir, "view-state.json")),
            ),
        ).also { autosave.start() }
    }
}
