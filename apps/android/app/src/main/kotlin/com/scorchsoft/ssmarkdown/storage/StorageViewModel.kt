package com.scorchsoft.ssmarkdown.storage

import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.document.SetAsideEdits
import com.scorchsoft.ssmarkdown.files.storage.StorageAccountant
import com.scorchsoft.ssmarkdown.history.versions.ClearRequest
import com.scorchsoft.ssmarkdown.history.versions.ClearResult
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.ExistingHistory
import com.scorchsoft.ssmarkdown.history.versions.ExportResult
import com.scorchsoft.ssmarkdown.history.versions.ImportResult
import com.scorchsoft.ssmarkdown.history.versions.VersionStore
import com.scorchsoft.ssmarkdown.nav.AppScreen
import com.scorchsoft.ssmarkdown.nav.ScreenEvent
import com.scorchsoft.ssmarkdown.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the Storage screen's view model is built from.
 *
 * @property exportsDir where exports wait (in app storage, so a clear never depends on the picker).
 * @property cacheDir the diagram and preview cache, the one rebuildable clear.
 * @property storageLow true under 500 MB free on the device.
 * @property openSetAsideAsCopy opens set-aside edits as a copy in the editor (R2-DS-3).
 */
data class StorageDependencies(
    val versions: VersionStore,
    val accountant: StorageAccountant,
    val settings: SettingsRepository,
    val store: DocumentStore,
    val exportsDir: File,
    val cacheDir: File,
    val storageLow: () -> Boolean,
    val clock: () -> Long,
    val scope: CoroutineScope,
    val io: CoroutineDispatcher,
    val openSetAsideAsCopy: (SetAsideEdits) -> Unit = {},
)

/**
 * Settings → Storage and history. Every clear goes through a
 * confirmation; with "Export first" ticked the versions are exported to
 * app storage and verified before anything is deleted, and the export
 * then waits there until the user picks where to keep it.
 */
@Suppress("TooManyFunctions") // One small function per clean-up, export and policy change.
class StorageViewModel(
    private val deps: StorageDependencies,
) {
    private var previewSkip: Set<DocumentKey> = emptySet()

    /** The set-aside edits the open confirmation listed: a clear deletes these and no others. */
    private var confirmedSetAside: List<SetAsideEdits> = emptyList()
    private val ui = MutableStateFlow(StorageUiState(policy = deps.settings.settings.value.history))
    private val eventChannel = Channel<ScreenEvent>(Channel.BUFFERED)

    /** The screen state. */
    val state: StateFlow<StorageUiState> = ui.asStateFlow()

    /** One-shot effects for the Activity. */
    val events: Flow<ScreenEvent> = eventChannel.receiveAsFlow()

    /** Refreshes every number: shows the last known breakdown at once, then measures. */
    fun load() {
        ui.update { it.copy(breakdown = deps.accountant.lastKnown, policy = deps.settings.settings.value.history) }
        deps.scope.launch {
            val breakdown = deps.accountant.measure()
            val (usage, cleanups, low) =
                withContext(deps.io) {
                    Triple(deps.versions.usage(), deps.versions.cleanupLog.read(), deps.storageLow())
                }
            val setAside = deps.store.setAsideEdits()
            ui.update {
                it.copy(
                    setAside = setAside,
                    breakdown = breakdown,
                    historyFiles = usage.files,
                    cleanups =
                        cleanups.sortedByDescending { e ->
                            e.timeMillis
                        },
                    lowStorage = low,
                )
            }
            refreshPreviews()
        }
    }

    /** The single entry point for user actions. */
    @Suppress("CyclomaticComplexMethod") // A dispatch table: one line per action.
    fun onAction(action: StorageAction) {
        val before = ui.value
        ui.update { StorageReducer.reduce(it, action) }
        when (action) {
            StorageAction.Back -> eventChannel.trySend(ScreenEvent.Back)
            is StorageAction.ChooseNotOpened, is StorageAction.ChooseOlderThan -> refreshPreviews()
            is StorageAction.AskClear -> askClear(action.kind, action.document)
            StorageAction.ConfirmClear -> before.confirm?.let(::clear)
            StorageAction.ToggleKeepHistory -> if (!before.policy.recording) savePolicy()
            StorageAction.TurnOffKeep -> turnOff(ExistingHistory.Keep)
            StorageAction.TurnOffExportThenDelete -> turnOff(ExistingHistory.ExportThenDelete(exportFile("history")))
            StorageAction.TurnOffConfirmDelete -> turnOff(ExistingHistory.Delete)
            is StorageAction.SetLimit, is StorageAction.SetForget -> savePolicy()
            is StorageAction.ExportFile -> export(action.document)
            is StorageAction.OpenHistory -> eventChannel.trySend(ScreenEvent.Open(AppScreen.History))
            StorageAction.ImportHistory -> eventChannel.trySend(ScreenEvent.PickImportZip)
            is StorageAction.OpenSetAside -> openSetAside(before, action.index)
            else -> Unit
        }
    }

    /** "Open as a copy" on a set-aside journal: the editor shows it in a new draft (R2-DS-3). */
    private fun openSetAside(
        state: StorageUiState,
        index: Int,
    ) {
        val edits = state.setAside.getOrNull(index) ?: return
        deps.openSetAsideAsCopy(edits)
        eventChannel.trySend(ScreenEvent.ToEditor)
    }

    /** The import picker answered: [zip] is a local copy, attached to [document]'s history. */
    fun onImportPicked(
        zip: File?,
        document: String?,
    ) {
        if (zip == null || document == null) return
        deps.scope.launch {
            val result = withContext(deps.io) { deps.versions.import(zip, DocumentKey(document)) }
            ui.update {
                it.copy(
                    message =
                        if (result is ImportResult.Imported) {
                            StorageMessage.Imported(
                                result.versions,
                            )
                        } else {
                            StorageMessage.Failed
                        },
                )
            }
            load()
        }
    }

    private fun refreshPreviews() {
        val state = ui.value
        val skip = openDirty()
        deps.scope.launch {
            val (notOpened, older) =
                withContext(deps.io) {
                    deps.versions.previewClear(ClearRequest.NotOpenedFor(state.notOpenedDays), skip) to
                        deps.versions.previewClear(ClearRequest.OlderThan(state.olderThanDays), skip)
                }
            ui.update { it.copy(notOpenedPreview = notOpened, olderPreview = older) }
        }
    }

    private fun askClear(
        kind: ClearKind,
        document: String?,
    ) {
        val state = ui.value
        if (kind == ClearKind.Cache) {
            ui.update { it.copy(confirm = StorageReducer.confirmFor(kind, null)) }
            return
        }
        if (kind == ClearKind.SetAside) {
            confirmedSetAside = state.setAside
            ui.update { it.copy(confirm = StorageReducer.confirmForSetAside(state.setAside)) }
            return
        }
        val probe = StorageReducer.confirmFor(kind, null, daysFor(kind, state), document)
        val request = probe.request ?: return
        // Read on the main thread, where the store lives, never from the I/O hop (DS-18).
        val skip = openDirty()
        previewSkip = skip
        deps.scope.launch {
            val preview = withContext(deps.io) { deps.versions.previewClear(request, skip) }
            ui.update {
                it.copy(
                    confirm = probe.copy(versions = preview.versions, files = preview.files, bytes = preview.bytes),
                    fileMenu = null,
                )
            }
        }
    }

    private fun daysFor(
        kind: ClearKind,
        state: StorageUiState,
    ): Int =
        when (kind) {
            ClearKind.NotOpened -> state.notOpenedDays
            ClearKind.OlderThan -> state.olderThanDays
            else -> 0
        }

    private fun clear(confirm: StorageConfirm) {
        ui.update { it.copy(confirm = null, busy = true) }
        if (confirm.kind == ClearKind.SetAside) {
            clearSetAside(confirm.exportFirst)
            return
        }
        deps.scope.launch {
            if (confirm.kind == ClearKind.Cache) {
                withContext(deps.io) { deps.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                ui.update { it.copy(busy = false) }
                load()
                return@launch
            }
            val request = confirm.request ?: return@launch
            val zip = if (confirm.exportFirst) exportFile("cleared") else null
            // Everything the confirmation promised to keep stays kept, plus anything dirty since (DS-18).
            val skip = previewSkip + openDirty()
            val result = withContext(deps.io) { deps.versions.clear(request, skip, zip) }
            finish(result, zip)
        }
    }

    /**
     * The confirmed clear of set-aside edits: exported and verified first
     * (unless unticked), and if the export fails nothing is deleted. Only
     * the edits the confirmation listed go; any set aside since stay.
     */
    private fun clearSetAside(exportFirst: Boolean) {
        val edits = confirmedSetAside
        confirmedSetAside = emptyList()
        deps.scope.launch {
            val zip = if (exportFirst) exportFile("set-aside-edits") else null
            if (zip != null && !deps.store.exportSetAsideEdits(edits, zip)) {
                ui.update { it.copy(busy = false, message = StorageMessage.Failed) }
                return@launch
            }
            val cleared = deps.store.clearSetAsideEdits(edits)
            ui.update {
                it.copy(
                    busy = false,
                    message =
                        StorageMessage.SetAsideCleared(
                            cleared,
                            edits.sumOf { e ->
                                e.sizeBytes
                            },
                        ),
                )
            }
            if (zip != null) eventChannel.trySend(ScreenEvent.SaveZip(zip, zip.name))
            load()
        }
    }

    private fun turnOff(existing: ExistingHistory) {
        deps.settings.update { it.copy(history = it.history.copy(recording = false)) }
        deps.scope.launch {
            val result = withContext(deps.io) { deps.versions.turnOff(existing) }
            finish(result, (existing as? ExistingHistory.ExportThenDelete)?.zip)
        }
    }

    private fun finish(
        result: ClearResult,
        zip: File?,
    ) {
        val message =
            when (result) {
                is ClearResult.Cleared -> StorageMessage.Cleared(result.versions, result.bytesFreed)
                else -> StorageMessage.Failed
            }
        ui.update { it.copy(busy = false, message = message) }
        if (zip != null && zip.exists()) eventChannel.trySend(ScreenEvent.SaveZip(zip, zip.name))
        load()
    }

    private fun export(document: String) {
        deps.scope.launch {
            val zip = exportFile("history")
            val result = withContext(deps.io) { deps.versions.export(listOf(DocumentKey(document)), zip) }
            if (result is ExportResult.Exported) {
                eventChannel.trySend(ScreenEvent.SaveZip(zip, zip.name))
            } else {
                ui.update { it.copy(message = StorageMessage.Failed) }
            }
        }
    }

    private fun savePolicy() {
        val policy = ui.value.policy
        deps.settings.update { it.copy(history = policy) }
        deps.versions.updatePolicy(policy)
    }

    /**
     * Documents whose History a clear must not touch: those with unsaved
     * edits, and those holding an earlier session's edits that could not
     * be applied (their History may be the only way back). Main thread only.
     */
    private fun openDirty(): Set<DocumentKey> =
        deps.store.openDocuments
            .filter { it.isDirty || it.holdsUnappliedEdits }
            .map { DocumentKey(it.uri.value) }
            .toSet()

    private fun exportFile(stem: String): File {
        deps.exportsDir.mkdirs()
        return File(deps.exportsDir, "ss-markdown-$stem-${deps.clock()}.zip")
    }
}
