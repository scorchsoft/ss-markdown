package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.saf.FolderRow
import com.scorchsoft.ssmarkdown.files.saf.unsavedCountsByFolder
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.folder.FolderEntryRow
import com.scorchsoft.ssmarkdown.folder.FolderNames
import com.scorchsoft.ssmarkdown.folder.FolderUiState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Folder mode (ux-spec "Folder mode"; quality-bar J7): the tree, switching
 * files without prompts, Save all with skipped conflicts listed, and
 * rename / duplicate / delete.
 */
@Suppress("TooManyFunctions") // One area (folder mode), one small function per file operation.
class FolderController(
    private val host: EditorHost,
) {
    private val deps get() = host.deps
    private var listed: List<FolderRow> = emptyList()

    /** The files the drawer currently marks unsaved. */
    private var shownDirty: Set<DocumentUri> = emptySet()

    /** The folder picker answered; null when cancelled. */
    fun onFolderPicked(treeUri: String?) {
        if (treeUri == null) return
        deps.permissions(treeUri, true)
        host.update {
            it.copy(
                folder = FolderUiState(FolderNames.nameOf(treeUri), treeUri, loading = true, drawerOpen = true),
            )
        }
        load()
        // A new folder grant may hold the open file: its pictures are looked up afresh.
        host.pictures.onFolderRefreshed()
    }

    /** A folder action; the reducer has already applied its pure part. */
    @Suppress("CyclomaticComplexMethod") // One branch per action.
    fun onAction(action: FolderAction) {
        when (action) {
            is FolderAction.OpenFile -> host.openDocument(action.uri)
            FolderAction.SaveAll -> saveAll()
            is FolderAction.Rename -> rename(action.uri, action.newName)
            is FolderAction.Duplicate -> duplicate(action.uri)
            is FolderAction.Delete -> fileOp(MessageKind.Deleted) { deps.folders.delete(action.uri) }
            FolderAction.NewFileHere -> newFile()
            FolderAction.OpenAnotherFolder -> host.emit(EditorEvent.PickFolder)
            FolderAction.CloseFolder -> close()
            FolderAction.ToggleShowAllFiles -> load()
            FolderAction.Refresh -> load().also { host.pictures.onFolderRefreshed() }
            else -> Unit
        }
    }

    /**
     * The open file was edited or saved: its row's dot follows, so a
     * docked panel, or a drawer opened later, never shows it clean while
     * it has unsaved edits (rubric #1). Only a clean ↔ unsaved change
     * redraws the rows; ordinary keystrokes cost one set lookup.
     */
    fun onActiveChanged(
        uri: DocumentUri,
        dirty: Boolean,
    ) {
        if (host.state.folder != null && (uri in shownDirty) != dirty) refreshDots()
    }

    /** Unsaved dots follow edits in any open buffer. */
    fun refreshDots() {
        if (host.state.folder == null) return
        host.update { state -> state.copy(folder = state.folder?.let { withRows(it, listed) }) }
    }

    private fun load() {
        val folder = host.state.folder ?: return
        deps.scope.launch {
            val result = withContext(deps.io) { deps.folders.list(folder.treeUri, null, folder.showAllFiles) }
            // Another folder (or the other file filter) was chosen while this one listed: its rows are not these.
            val now = host.state.folder
            if (now?.treeUri != folder.treeUri || now.showAllFiles != folder.showAllFiles) return@launch
            when (result) {
                is ProviderResult.Ok -> {
                    listed = result.value
                    host.update { s ->
                        s.copy(
                            folder = s.folder?.let { withRows(it, listed).copy(loading = false, listFailed = false) },
                        )
                    }
                }

                is ProviderResult.Failure -> {
                    host.update { s -> s.copy(folder = s.folder?.copy(loading = false, listFailed = true)) }
                }
            }
        }
    }

    private fun withRows(
        folder: FolderUiState,
        rows: List<FolderRow>,
    ): FolderUiState {
        val dirty =
            deps.store.dirtyDocuments
                .map { it.uri }
                .toSet()
        shownDirty = dirty
        val marked = rows.map { it.copy(dirty = it.entry.uri in dirty) }
        val counts = marked.unsavedCountsByFolder()
        val active = host.active?.open?.uri
        val entries =
            marked.map { row ->
                FolderEntryRow(
                    uri = row.entry.uri.value,
                    name = row.entry.name,
                    depth = row.depth,
                    isDirectory = row.entry.isDirectory,
                    dirty = row.dirty,
                    changedOutside = deps.store[row.entry.uri]?.conflict != null || row.changedOutside,
                    unsavedInside = counts[row.entry.uri] ?: 0,
                    muted = !row.entry.isDirectory && !FolderNames.isMarkdown(row.entry.name),
                    current = row.entry.uri == active,
                )
            }
        return folder.copy(rows = entries, fileCount = entries.count { !it.isDirectory }, unsavedCount = dirty.size)
    }

    private fun saveAll() {
        deps.scope.launch {
            var saved = 0
            val skipped = ArrayList<SkippedSave>()
            for (doc in deps.store.dirtyDocuments.filterNot { it.uri.isDraft }) {
                val outcome = deps.store.save(doc.uri)
                val problem = SaveProblem.of(outcome)
                if (problem == null) {
                    saved++
                } else {
                    skipped += SkippedSave(doc.uri.value, doc.displayName ?: doc.uri.value, problem)
                }
            }
            host.update {
                it.copy(
                    banner = EditorBanner.SaveAllResult(saved, skipped),
                    folder = it.folder?.copy(drawerOpen = false),
                )
            }
            if (skipped.isEmpty()) host.emit(EditorEvent.Tick) else host.emit(EditorEvent.ErrorTick)
            refreshDots()
        }
    }

    /**
     * Renames a file. A provider whose ids are paths gives the renamed file
     * a new URI, so an open file follows it: unsaved edits must be saved
     * first (their journal and base copy belong to the old URI), and a clean
     * file on screen is reopened under its new name rather than left on a
     * URI that no longer exists ("File no longer exists").
     */
    private fun rename(
        uri: String,
        newName: String,
    ) {
        val before = deps.store[DocumentUri(uri)]
        val unsaved = before?.run { isDirty || conflict != null || holdsUnappliedEdits } == true
        if (unsaved) {
            host.message(MessageKind.SaveBeforeRename)
            return
        }
        deps.scope.launch {
            when (val renamed = withContext(deps.io) { deps.folders.rename(uri, newName) }) {
                is ProviderResult.Ok -> {
                    host.message(MessageKind.Renamed)
                    val open = deps.store[DocumentUri(uri)]
                    // Typing that slipped in while the provider renamed stays on its buffer, journalled.
                    val follow =
                        host.active
                            ?.open
                            ?.uri
                            ?.value == uri && open?.isDirty != true
                    if (follow && renamed.value.value != uri) host.openDocument(renamed.value.value)
                }

                is ProviderResult.Failure -> {
                    host.message(MessageKind.ActionFailed)
                }
            }
            load()
        }
    }

    private fun duplicate(uri: String) {
        val folder = host.state.folder ?: return
        val row = folder.rows.firstOrNull { it.uri == uri } ?: return
        val parent = parentOf(folder, row) ?: deps.folders.rootOf(folder.treeUri)
        fileOp(MessageKind.Duplicated) { deps.folders.duplicate(uri, parent, FolderNames.copyName(row.name)) }
    }

    private fun parentOf(
        folder: FolderUiState,
        row: FolderEntryRow,
    ): String? {
        val index = folder.rows.indexOf(row)
        return folder.rows
            .take(index)
            .lastOrNull { it.isDirectory && it.depth < row.depth }
            ?.uri
    }

    private fun newFile() {
        val folder = host.state.folder ?: return
        deps.scope.launch {
            val created =
                withContext(deps.io) { deps.folders.create(deps.folders.rootOf(folder.treeUri), NEW_FILE_NAME) }
            when (created) {
                is ProviderResult.Ok -> {
                    load()
                    host.openDocument(created.value.value)
                }

                is ProviderResult.Failure -> {
                    host.message(MessageKind.ActionFailed)
                }
            }
        }
    }

    private fun close() {
        if (deps.store.dirtyDocuments.any { !it.uri.isDraft }) {
            host.update { it.copy(overlay = EditorOverlay.DirtyPrompt(PendingLeave.CloseFolder)) }
        } else {
            host.update { it.copy(folder = null) }
        }
    }

    private fun fileOp(
        done: MessageKind,
        op: () -> ProviderResult<*>,
    ) {
        deps.scope.launch {
            when (withContext(deps.io) { op() }) {
                is ProviderResult.Ok -> host.message(done)
                is ProviderResult.Failure -> host.message(MessageKind.ActionFailed)
            }
            load()
        }
    }

    private companion object {
        const val NEW_FILE_NAME = "Untitled.md"
    }
}
