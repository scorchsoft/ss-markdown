package com.scorchsoft.ssmarkdown.folder

/** What the folder drawer (or docked panel) can do. */
sealed interface FolderAction {
    data object OpenDrawer : FolderAction

    data object CloseDrawer : FolderAction

    data object TogglePanel : FolderAction

    data class Filter(
        val text: String,
    ) : FolderAction

    data class OpenFile(
        val uri: String,
    ) : FolderAction

    data object SaveAll : FolderAction

    data class RowMenu(
        val uri: String,
    ) : FolderAction

    data class StartRename(
        val uri: String,
    ) : FolderAction

    data class Rename(
        val uri: String,
        val newName: String,
    ) : FolderAction

    data class Duplicate(
        val uri: String,
    ) : FolderAction

    data class AskDelete(
        val uri: String,
    ) : FolderAction

    data class Delete(
        val uri: String,
    ) : FolderAction

    data object DismissAction : FolderAction

    data object NewFileHere : FolderAction

    data object OpenAnotherFolder : FolderAction

    data object CloseFolder : FolderAction

    data object ToggleShowAllFiles : FolderAction

    data object Refresh : FolderAction
}
