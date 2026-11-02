package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.find.FindReducer
import com.scorchsoft.ssmarkdown.folder.FolderReducer
import com.scorchsoft.ssmarkdown.navigator.NavigatorReducer
import com.scorchsoft.ssmarkdown.ui.strip.StripKey
import com.scorchsoft.ssmarkdown.ui.strip.StripPage
import com.scorchsoft.ssmarkdown.ui.strip.StripPaging

/**
 * The editor's pure state transitions: everything an action changes on
 * screen without touching a file. Side effects (saving, merging,
 * clipboard) run in [EditorViewModel], which feeds their results back as
 * state. Pure, so every state machine on the screen is a host-JVM test.
 */
object EditorReducer {
    /** Zoom moves in 10 % steps between 50 % and 300 %, matching the editor's pinch range. */
    const val ZOOM_STEP: Int = 10
    const val MIN_ZOOM: Int = 50
    const val MAX_ZOOM: Int = 300

    /** [state] after [action]. Actions with no pure part return [state] unchanged. */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // A dispatch table: one short branch per action.
    fun reduce(
        state: EditorUiState,
        action: EditorAction,
    ): EditorUiState =
        when (action) {
            is EditorAction.SelectMode -> {
                state.copy(mode = action.mode)
            }

            EditorAction.OpenMenu -> {
                state.copy(overlay = EditorOverlay.Menu)
            }

            is EditorAction.OpenView -> {
                state.copy(overlay = EditorOverlay.Display(action.highlight))
            }

            EditorAction.DismissOverlay,
            EditorAction.KeepEditing,
            EditorAction.ConfirmNotKept,
            is EditorAction.LinkDone,
            EditorAction.LinkRemove,
            EditorAction.CopyPictureIn,
            -> {
                state.copy(overlay = null)
            }

            EditorAction.AskUseMine -> {
                state.copy(overlay = EditorOverlay.ConfirmUseMine)
            }

            EditorAction.BigFileChipTapped -> {
                state.copy(overlay = EditorOverlay.BigFileInfo)
            }

            EditorAction.RecoveryGotIt,
            EditorAction.DismissBanner,
            EditorAction.DiscardKeptEdits,
            EditorAction.OpenAnotherFile,
            EditorAction.RetryOpen,
            -> {
                state.copy(banner = null)
            }

            EditorAction.MessageShown -> {
                state.copy(message = null)
            }

            EditorAction.ShowFailureDetails -> {
                failureDetails(state)
            }

            EditorAction.BackPressed -> {
                back(state)
            }

            is EditorAction.ResetZoom -> {
                zoom(state, action.pane) {
                    if (action.pane ==
                        Pane.Code
                    ) {
                        state.defaultCodeZoom
                    } else {
                        state.defaultPreviewZoom
                    }
                }
            }

            is EditorAction.StepZoom -> {
                zoom(state, action.pane) { it + action.steps * ZOOM_STEP }
            }

            is EditorAction.ZoomChanged -> {
                pinch(state, action)
            }

            is EditorAction.SetCodeWrap -> {
                state.copy(codeWrap = action.wrap)
            }

            is EditorAction.StripKeyTapped -> {
                strip(state, action.key)
            }

            is EditorAction.ImeVisibilityChanged -> {
                ime(state, action.visible)
            }

            is EditorAction.Find -> {
                state.copy(find = FindReducer.reduce(state.find, action.action))
            }

            is EditorAction.Navigator -> {
                state.copy(navigator = NavigatorReducer.reduce(state.navigator, action.action))
            }

            is EditorAction.Folder -> {
                state.copy(folder = state.folder?.let { FolderReducer.reduce(it, action.action) })
            }

            else -> {
                state
            }
        }

    /**
     * Back closes the innermost thing first: an overlay, the find bar, the
     * Navigator, the drawer. Only then does a dirty document ask Save /
     * Don't save / Keep editing (robustness §6). A clean document is left
     * unchanged: the view model finishes.
     */
    fun back(state: EditorUiState): EditorUiState =
        when {
            state.overlay != null -> state.copy(overlay = null)
            state.navigator != null -> state.copy(navigator = null)
            state.find != null -> state.copy(find = null)
            state.folder?.drawerOpen == true -> state.copy(folder = state.folder.copy(drawerOpen = false))
            state.isDirty -> state.copy(overlay = EditorOverlay.DirtyPrompt(PendingLeave.Exit))
            else -> state
        }

    /** True when Back has nothing left to close and nothing to ask: the app may leave. */
    fun backLeaves(state: EditorUiState): Boolean =
        state.overlay == null &&
            state.navigator == null &&
            state.find == null &&
            state.folder?.drawerOpen != true &&
            !state.isDirty

    private fun failureDetails(state: EditorUiState): EditorUiState {
        val banner = state.banner as? EditorBanner.SaveFailed ?: return state
        return state.copy(overlay = EditorOverlay.SaveFailureDetails(banner.problem))
    }

    private inline fun zoom(
        state: EditorUiState,
        pane: Pane,
        change: (Int) -> Int,
    ): EditorUiState =
        when (pane) {
            Pane.Code -> state.copy(codeZoom = change(state.codeZoom).coerceIn(MIN_ZOOM, MAX_ZOOM))
            Pane.Preview -> state.copy(previewZoom = change(state.previewZoom).coerceIn(MIN_ZOOM, MAX_ZOOM))
        }

    private fun pinch(
        state: EditorUiState,
        action: EditorAction.ZoomChanged,
    ): EditorUiState {
        val settled = zoom(state, action.pane) { action.percent }
        return settled.copy(pinchPercent = if (action.pinching) action.percent else null)
    }

    private fun strip(
        state: EditorUiState,
        key: StripKey,
    ): EditorUiState = state.copy(strip = state.strip.copy(page = StripPaging.afterKey(state.strip.page, key)))

    private fun ime(
        state: EditorUiState,
        visible: Boolean,
    ): EditorUiState =
        if (visible) {
            state
        } else {
            // The symbols page never outlives the keyboard; it reopens on formatting.
            state.copy(strip = state.strip.copy(page = StripPage.Formatting))
        }
}
