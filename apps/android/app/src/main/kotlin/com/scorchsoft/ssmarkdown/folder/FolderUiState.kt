package com.scorchsoft.ssmarkdown.folder

import androidx.compose.runtime.Immutable

/**
 * One row of the folder tree (ux-spec "Folder mode").
 *
 * @property unsavedInside a collapsed folder's "n unsaved".
 * @property changedOutside the "Changed" label: the file differs from what we loaded.
 * @property muted `.txt` and other non-Markdown files are muted and open in Code only.
 */
@Immutable
data class FolderEntryRow(
    val uri: String,
    val name: String,
    val depth: Int,
    val isDirectory: Boolean,
    val dirty: Boolean = false,
    val changedOutside: Boolean = false,
    val unsavedInside: Int = 0,
    val muted: Boolean = false,
    val current: Boolean = false,
)

/** A pending rename, duplicate or delete on one row. */
sealed interface FileAction {
    /** The row's ⋯ (or long-press) menu: Rename · Duplicate · Delete. */
    data class Menu(
        val uri: String,
        val name: String,
    ) : FileAction

    /** The rename field, prefilled with [name]. */
    data class Rename(
        val uri: String,
        val name: String,
    ) : FileAction

    /** "Delete <name>?" — the file goes; its history entry stays. */
    data class ConfirmDelete(
        val uri: String,
        val name: String,
    ) : FileAction
}

/**
 * The open folder: the drawer on phones, the docked panel at ≥ 840 dp.
 *
 * @property treeUri the persisted tree grant.
 * @property fileCount files listed, for "Documents · 14 files".
 * @property filter the "Find a file" field.
 * @property drawerOpen the modal drawer on phones; ignored when docked.
 */
@Immutable
data class FolderUiState(
    val name: String,
    val treeUri: String,
    val rows: List<FolderEntryRow> = emptyList(),
    val fileCount: Int = 0,
    val unsavedCount: Int = 0,
    val filter: String = "",
    val showAllFiles: Boolean = false,
    val drawerOpen: Boolean = false,
    val panelCollapsed: Boolean = false,
    val action: FileAction? = null,
    val loading: Boolean = false,
    val listFailed: Boolean = false,
) {
    /** Rows matching [filter] by name (folders stay so the tree keeps its shape when not filtering). */
    val visibleRows: List<FolderEntryRow>
        get() =
            if (filter.isBlank()) {
                rows
            } else {
                rows.filter { !it.isDirectory && it.name.contains(filter.trim(), ignoreCase = true) }
            }
}
