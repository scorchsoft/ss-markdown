package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.document.DiskReader
import com.scorchsoft.ssmarkdown.document.VersionHistoryHook
import com.scorchsoft.ssmarkdown.files.document.AutosaveScheduler
import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.drafts.DraftStore
import com.scorchsoft.ssmarkdown.folder.FolderSource
import com.scorchsoft.ssmarkdown.history.versions.VersionStore
import com.scorchsoft.ssmarkdown.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * What the editor's view model is built from. One constructor argument
 * instead of twelve, so tests swap any of them (docs/engineering.md:
 * everything under test can be swapped out).
 *
 * @property permissions persists a picked URI's grant: `(uri, write) -> held`.
 * @property lastExitWasSwipe true when the previous process was swiped from Recents.
 * @property scope the main-thread scope the store and its callers live on.
 * @property io where blocking file-system calls run.
 */
data class EditorDependencies(
    val store: DocumentStore,
    val autosave: AutosaveScheduler,
    val drafts: DraftStore,
    val history: VersionHistoryHook,
    val versions: VersionStore,
    val settings: SettingsRepository,
    val diskReader: DiskReader,
    val folders: FolderSource,
    val permissions: (String, Boolean) -> Boolean,
    val clock: () -> Long,
    val lastExitWasSwipe: () -> Boolean = { false },
    val scope: CoroutineScope,
    val io: CoroutineDispatcher,
)

/**
 * Per-document facts the store does not keep: when it was saved and by
 * whom, where it came from. Lives as long as the process.
 *
 * @property readOnlySource the app that handed over a read-only copy ("from Gmail").
 * @property fromAnotherApp first open from another app shows Preview (ux-spec "Saving and safety").
 */
data class DocumentMeta(
    val openedAtMillis: Long,
    val lastSavedAtMillis: Long? = null,
    val lastSaveWasAutosave: Boolean = false,
    val saving: Boolean = false,
    val lastFailure: SaveProblem? = null,
    val readOnlySource: String? = null,
    val fromAnotherApp: Boolean = false,
    val mode: ViewMode? = null,
    val codeZoom: Int? = null,
    val previewZoom: Int? = null,
)

/**
 * What the editor's controllers need from the view model. Each
 * controller owns one area (saving, conflicts, find…) and reaches the
 * shared state only through this, so each can be read on its own.
 */
interface EditorHost {
    /** The collaborators. */
    val deps: EditorDependencies

    /** The document on screen, or null while none is open. */
    val active: ActiveDocument?

    /** The current screen state. */
    val state: EditorUiState

    /** Changes the screen state. */
    fun update(change: (EditorUiState) -> EditorUiState)

    /** Sends a one-shot effect to the Activity. */
    fun emit(event: EditorEvent)

    /** Recomputes everything derived from the document (save state, title, breadcrumb). */
    fun refresh()

    /** The facts kept about [uri]. */
    fun meta(uri: String): DocumentMeta

    /** Changes the facts kept about [uri]. */
    fun updateMeta(
        uri: String,
        change: (DocumentMeta) -> DocumentMeta,
    )

    /** Shows a short message. */
    fun message(
        kind: MessageKind,
        argument: String = "",
        undo: Boolean = false,
    )

    /** Opens [uri] on screen (already-open buffers are joined, never reloaded). */
    fun openDocument(
        uri: String,
        fromAnotherApp: Boolean = false,
        readOnlySource: String? = null,
    )

    /** Starts a new untitled draft. */
    fun newDraft()

    /** Re-binds the screen to the store's current buffer for the active URI (after a reload or a resolve). */
    fun rebind()
}
