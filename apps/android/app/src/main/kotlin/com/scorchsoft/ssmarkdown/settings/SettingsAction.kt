package com.scorchsoft.ssmarkdown.settings

import com.scorchsoft.ssmarkdown.editor.EditorReducer
import com.scorchsoft.ssmarkdown.ui.strip.StripRows

/** What the Settings screen can do. */
sealed interface SettingsAction {
    data class SetOpenWith(
        val value: OpenWith,
    ) : SettingsAction

    data class SetOnLaunch(
        val value: OnLaunch,
    ) : SettingsAction

    data class StepCodeZoom(
        val steps: Int,
    ) : SettingsAction

    data class StepPreviewZoom(
        val steps: Int,
    ) : SettingsAction

    /** Text → Show line numbers: the Code view's gutter on or off. */
    data class SetLineNumbers(
        val show: Boolean,
    ) : SettingsAction

    /** Text → Formatting bar: one row or two above the keyboard. */
    data class SetFormattingBar(
        val value: StripRows,
    ) : SettingsAction

    data class SetSaveMode(
        val value: SaveMode,
    ) : SettingsAction

    data object ToggleTapToEdit : SettingsAction

    data object ToggleScrollTogether : SettingsAction

    /** Sharing → "Show recent files in the share sheet". */
    data object ToggleDirectShare : SettingsAction

    data object OpenStorage : SettingsAction

    data object OpenAbout : SettingsAction

    data object Back : SettingsAction
}

/** Settings' pure changes. Navigation actions return [settings] unchanged. */
object SettingsReducer {
    /** [settings] after [action]. */
    fun reduce(
        settings: AppSettings,
        action: SettingsAction,
    ): AppSettings =
        when (action) {
            is SettingsAction.SetOpenWith -> {
                settings.copy(openWith = action.value)
            }

            is SettingsAction.SetOnLaunch -> {
                settings.copy(onLaunch = action.value)
            }

            is SettingsAction.StepCodeZoom -> {
                settings.copy(
                    defaultZoomCode = step(settings.defaultZoomCode, action.steps),
                )
            }

            is SettingsAction.StepPreviewZoom -> {
                settings.copy(
                    defaultZoomPreview = step(settings.defaultZoomPreview, action.steps),
                )
            }

            is SettingsAction.SetLineNumbers -> {
                settings.copy(lineNumbers = action.show)
            }

            is SettingsAction.SetFormattingBar -> {
                settings.copy(formattingBar = action.value)
            }

            is SettingsAction.SetSaveMode -> {
                settings.copy(saveMode = action.value)
            }

            SettingsAction.ToggleTapToEdit -> {
                settings.copy(tapToEditPreview = !settings.tapToEditPreview)
            }

            SettingsAction.ToggleScrollTogether -> {
                settings.copy(scrollPanesTogether = !settings.scrollPanesTogether)
            }

            SettingsAction.ToggleDirectShare -> {
                settings.copy(share = settings.share.copy(directShare = !settings.share.directShare))
            }

            else -> {
                settings
            }
        }

    private fun step(
        percent: Int,
        steps: Int,
    ) = (percent + steps * EditorReducer.ZOOM_STEP).coerceIn(EditorReducer.MIN_ZOOM, EditorReducer.MAX_ZOOM)
}
