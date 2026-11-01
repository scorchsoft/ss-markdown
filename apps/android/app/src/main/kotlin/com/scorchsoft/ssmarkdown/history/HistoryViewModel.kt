package com.scorchsoft.ssmarkdown.history

import com.scorchsoft.ssmarkdown.conflict.CompareOrigin
import com.scorchsoft.ssmarkdown.conflict.CompareUiState
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.files.saf.SafeFileName
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.ExportContents
import com.scorchsoft.ssmarkdown.history.versions.ExportResult
import com.scorchsoft.ssmarkdown.history.versions.HistoryListing
import com.scorchsoft.ssmarkdown.history.versions.LineDiff
import com.scorchsoft.ssmarkdown.history.versions.ReadResult
import com.scorchsoft.ssmarkdown.history.versions.VersionId
import com.scorchsoft.ssmarkdown.history.versions.VersionInfo
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.history.versions.VersionStore
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.nav.ScreenEvent
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.CoroutineDispatcher
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
import java.time.ZoneId

/** What the History screen can do. */
sealed interface HistoryAction {
    data object Back : HistoryAction

    /** The user said which document in an imported export is this file's. */
    data class ImportAs(
        val sourceUri: String,
    ) : HistoryAction

    /** The import choice was dismissed: nothing is imported. */
    data object CancelImport : HistoryAction

    data class Expand(
        val id: VersionId,
    ) : HistoryAction

    data class ExpandRun(
        val key: String,
    ) : HistoryAction

    data class CompareWithNow(
        val id: VersionId,
    ) : HistoryAction

    data class Restore(
        val id: VersionId,
    ) : HistoryAction

    data class StartPin(
        val info: VersionInfo,
    ) : HistoryAction

    data class Pin(
        val id: VersionId,
        val name: String,
    ) : HistoryAction

    data class Unpin(
        val id: VersionId,
    ) : HistoryAction

    data object CancelPin : HistoryAction

    data object ToggleAutosave : HistoryAction

    data object Export : HistoryAction

    data object Import : HistoryAction

    data object TurnOn : HistoryAction
}

/**
 * Version history of the document on screen (ux-spec "Version
 * history"). Restoring never overwrites: the current text is recorded
 * as "Before restore" first, and the restore is one undo step.
 */
class HistoryViewModel(
    private val versions: VersionStore,
    private val editor: EditorViewModel,
    private val io: CoroutineDispatcher,
    private val exportsDir: File,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val ui = MutableStateFlow(HistoryUiState())
    private val eventChannel = Channel<ScreenEvent>(Channel.BUFFERED)
    private var listed: List<VersionInfo> = emptyList()
    private var pendingImport: File? = null
    private val deps get() = editor.deps

    /** The screen state. */
    val state: StateFlow<HistoryUiState> = ui.asStateFlow()

    /** One-shot effects for the Activity. */
    val events: Flow<ScreenEvent> = eventChannel.receiveAsFlow()

    private val key: DocumentKey? get() =
        editor.active
            ?.open
            ?.uri
            ?.value
            ?.let(::DocumentKey)

    /** Loads the active document's history. */
    fun load() {
        val doc = editor.active ?: return
        val uri = doc.open.uri
        val settings = deps.settings.settings.value
        ui.update {
            it.copy(
                fileName = doc.open.displayName ?: "",
                isDraft = uri.isDraft,
                recording = settings.history.recording,
                autosaveThisFile = settings.autosaveOverrides[uri.value],
                loading = true,
            )
        }
        deps.scope.launch {
            val listing = withContext(io) { versions.list(DocumentKey(uri.value)) }
            when (listing) {
                is HistoryListing.Listed -> {
                    listed = listing.versions
                    ui.update {
                        it.copy(
                            versionCount = listing.versions.size,
                            bytes = listing.usage.bytes,
                            loading = false,
                            unreadable = false,
                        )
                    }
                    regroup()
                }

                HistoryListing.Empty -> {
                    ui.update { it.copy(versionCount = 0, days = emptyList(), loading = false) }
                }

                else -> {
                    ui.update { it.copy(loading = false, unreadable = true) }
                }
            }
        }
    }

    /** The single entry point for user actions. */
    @Suppress("CyclomaticComplexMethod") // A dispatch table: one line per action.
    fun onAction(action: HistoryAction) {
        when (action) {
            HistoryAction.Back -> {
                eventChannel.trySend(ScreenEvent.Back)
            }

            is HistoryAction.Expand -> {
                expand(action.id)
            }

            is HistoryAction.ExpandRun -> {
                ui
                    .update {
                        it.copy(
                            expandedRuns = it.expandedRuns + action.key,
                        )
                    }.also { regroup() }
            }

            is HistoryAction.CompareWithNow -> {
                compare(action.id)
            }

            is HistoryAction.Restore -> {
                restore(action.id)
            }

            is HistoryAction.StartPin -> {
                ui.update { it.copy(pinning = action.info) }
            }

            is HistoryAction.Pin -> {
                pin(action.id, true, action.name.ifBlank { null })
            }

            is HistoryAction.Unpin -> {
                pin(action.id, false, null)
            }

            HistoryAction.CancelPin -> {
                ui.update { it.copy(pinning = null) }
            }

            HistoryAction.ToggleAutosave -> {
                editor.onAction(EditorAction.ToggleAutosaveThisFile).also { load() }
            }

            HistoryAction.Export -> {
                export()
            }

            HistoryAction.Import -> {
                eventChannel.trySend(ScreenEvent.PickImportZip)
            }

            is HistoryAction.ImportAs -> {
                val zip = pendingImport
                pendingImport = null
                ui.update { it.copy(importChoices = emptyList()) }
                if (zip != null) deps.scope.launch { importFrom(zip, action.sourceUri) }
            }

            HistoryAction.CancelImport -> {
                pendingImport = null
                ui.update { it.copy(importChoices = emptyList()) }
            }

            HistoryAction.TurnOn -> {
                turnOn()
            }
        }
    }

    /** The import picker answered with a local copy of the zip. */
    fun onImportPicked(zip: File?) {
        val doc = editor.active ?: return
        if (zip == null) return
        val name = doc.open.displayName.orEmpty()
        val text = doc.text.snapshot()
        deps.scope.launch {
            val match =
                withContext(io) {
                    when (val contents = versions.listExport(zip)) {
                        is ExportContents.Listed -> {
                            ImportMatcher.match(contents.documents, name, ImportMatcher.sha256Hex(text))
                        }

                        else -> {
                            ImportMatch.Empty
                        }
                    }
                }
            when (match) {
                is ImportMatch.Matched -> {
                    importFrom(zip, match.document.uri)
                }

                is ImportMatch.Ask -> {
                    pendingImport = zip
                    ui.update { it.copy(importChoices = match.choices) }
                }

                ImportMatch.Empty -> {
                    editor.message(MessageKind.ActionFailed)
                }
            }
        }
    }

    /** Attaches one document of an export (by its source URI) to this file's history, as new branches. */
    private val importFrom: suspend (File, String) -> Unit = { zip, sourceUri ->
        key?.let { document ->
            withContext(io) { versions.import(zip, document, sourceUri) }
            load()
        }
    }

    private fun regroup() {
        val days = HistoryTimeline.group(listed, deps.clock(), zone(), ui.value.expandedRuns)
        ui.update { it.copy(days = days) }
    }

    private fun expand(id: VersionId) {
        if (ui.value.expanded == id) {
            ui.update { it.copy(expanded = null, diff = emptyList()) }
            return
        }
        val document = key ?: return
        val index = listed.indexOfFirst { it.id == id }
        val previous =
            listed
                .filter {
                    it.id.seq < id.seq && it.branch == listed[index].branch
                }.maxByOrNull { it.id.seq }
        deps.scope.launch {
            val lines =
                withContext(io) {
                    val now = versions.textOf(document, id) ?: return@withContext null
                    val before = previous?.let { versions.textOf(document, it.id) }.orEmpty()
                    LineDiff
                        .compute(before, now, context = 1)
                        .hunks
                        .flatMap { it.lines }
                        .take(MAX_DIFF_LINES)
                }
            ui.update { it.copy(expanded = id, diff = lines.orEmpty(), versionDamaged = lines == null) }
        }
    }

    private fun compare(id: VersionId) {
        val document = key ?: return
        val current =
            editor.active
                ?.text
                ?.snapshot()
                ?.toString() ?: return
        deps.scope.launch {
            val old = withContext(io) { versions.textOf(document, id) }
            if (old == null) {
                ui.update { it.copy(versionDamaged = true) }
                return@launch
            }
            editor.conflicts.showCompare(CompareUiState.of(ui.value.fileName, CompareOrigin.History, old, current))
        }
    }

    private fun restore(id: VersionId) {
        val document = key ?: return
        val doc = editor.active ?: return
        deps.scope.launch {
            val text = withContext(io) { versions.textOf(document, id) }
            if (text == null) {
                ui.update { it.copy(versionDamaged = true) }
                return@launch
            }
            val current = doc.text.snapshot()
            deps.history.record(doc.open.uri, current, VersionKind.BeforeRestore)
            doc.surface.apply(listOf(EditOp(0, current, text)), TextSelection.caret(0))
            editor.message(MessageKind.Restored)
            eventChannel.trySend(ScreenEvent.Back)
        }
    }

    private fun pin(
        id: VersionId,
        pinned: Boolean,
        name: String?,
    ) {
        val document = key ?: return
        ui.update { it.copy(pinning = null) }
        deps.scope.launch {
            withContext(io) { versions.setPinned(document, id, pinned, name) }
            load()
        }
    }

    private fun export() {
        val document = key ?: return
        deps.scope.launch {
            exportsDir.mkdirs()
            // The display name comes from the file's provider and may say "../" (SEC-1).
            val zip =
                SafeFileName.inside(
                    exportsDir,
                    "${SafeFileName.of(ui.value.fileName.ifEmpty { "draft" })}-history.zip",
                )
            val result = withContext(io) { versions.export(listOf(document), zip) }
            if (result is ExportResult.Exported) eventChannel.trySend(ScreenEvent.SaveZip(zip, zip.name))
        }
    }

    private fun turnOn() {
        deps.settings.update { it.copy(history = it.history.copy(recording = true)) }
        versions.updatePolicy(deps.settings.settings.value.history)
        load()
    }

    private companion object {
        const val MAX_DIFF_LINES = 12
    }
}

/** The text of a version, or null when it is damaged, missing or from a newer app: callers say so (CC-17). Blocking. */
private fun VersionStore.textOf(
    document: DocumentKey,
    id: VersionId,
): String? = (read(document, id) as? ReadResult.Text)?.text
