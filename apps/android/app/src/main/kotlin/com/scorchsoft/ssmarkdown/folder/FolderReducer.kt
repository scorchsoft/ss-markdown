package com.scorchsoft.ssmarkdown.folder

/** The folder drawer's pure transitions; file operations run in the folder controller. */
object FolderReducer {
    /** [state] after [action]. */
    @Suppress("CyclomaticComplexMethod") // A dispatch table: one short branch per action.
    fun reduce(
        state: FolderUiState,
        action: FolderAction,
    ): FolderUiState =
        when (action) {
            FolderAction.OpenDrawer -> {
                state.copy(drawerOpen = true)
            }

            FolderAction.CloseDrawer -> {
                state.copy(drawerOpen = false, filter = "")
            }

            FolderAction.TogglePanel -> {
                state.copy(panelCollapsed = !state.panelCollapsed)
            }

            is FolderAction.Filter -> {
                state.copy(filter = action.text)
            }

            is FolderAction.OpenFile -> {
                state.copy(drawerOpen = false)
            }

            is FolderAction.RowMenu -> {
                withAction(state, action.uri) { FileAction.Menu(action.uri, it) }
            }

            is FolderAction.StartRename -> {
                withAction(state, action.uri) { FileAction.Rename(action.uri, it) }
            }

            is FolderAction.AskDelete -> {
                withAction(state, action.uri) { FileAction.ConfirmDelete(action.uri, it) }
            }

            FolderAction.DismissAction, is FolderAction.Rename, is FolderAction.Delete, is FolderAction.Duplicate -> {
                state.copy(action = null)
            }

            FolderAction.ToggleShowAllFiles -> {
                state.copy(showAllFiles = !state.showAllFiles)
            }

            else -> {
                state
            }
        }

    private inline fun withAction(
        state: FolderUiState,
        uri: String,
        make: (String) -> FileAction,
    ): FolderUiState {
        val name = state.rows.firstOrNull { it.uri == uri }?.name ?: return state
        return state.copy(action = make(name))
    }
}
