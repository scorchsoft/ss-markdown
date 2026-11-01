package com.scorchsoft.ssmarkdown.themes

import com.scorchsoft.ssmarkdown.settings.SettingsRepository
import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.codec.ThemeCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/** One-shot effects the theme editor's route performs: pickers and navigation. */
sealed interface ThemeEditorEvent {
    /** Export: the system "create document" picker, then write [json]. */
    data class ExportJson(
        val suggestedName: String,
        val json: String,
    ) : ThemeEditorEvent

    /** Import: the system picker for a `.json` theme. */
    data object PickImport : ThemeEditorEvent

    /** The font picker for [role]. */
    data class PickFont(
        val role: FontRole,
    ) : ThemeEditorEvent

    /** Saved, or Back: leave the editor. */
    data object Close : ThemeEditorEvent
}

/**
 * The theme editor's view model. Built-ins are read-only, so editing one
 * starts a custom copy; "Use this theme" saves it to the [library] and
 * makes it the preview theme. The editing itself is [ThemeEditorReducer].
 */
class ThemeEditorViewModel(
    private val library: ThemeLibrary,
    private val settings: SettingsRepository,
) {
    private val ui = MutableStateFlow(ThemeEditorUiState())
    private val eventChannel = Channel<ThemeEditorEvent>(Channel.BUFFERED)

    /** The screen state. */
    val state: StateFlow<ThemeEditorUiState> = ui.asStateFlow()

    /** One-shot effects for the route. */
    val events: Flow<ThemeEditorEvent> = eventChannel.receiveAsFlow()

    /** Opens the editor on the theme [id]: a custom theme itself, or a copy of a built-in. */
    fun open(id: String) {
        val theme = library.byId(id)
        ui.value =
            if (BuiltInThemes.byId(theme.id) != null) {
                ThemeEditorReducer.start(ThemeEditorReducer.copyOf(theme), savedId = null)
            } else {
                ThemeEditorReducer.start(theme, savedId = theme.id)
            }
    }

    /** A theme editor action. */
    fun onAction(action: ThemeEditorAction) {
        when (action) {
            ThemeEditorAction.Import -> {
                eventChannel.trySend(ThemeEditorEvent.PickImport)
            }

            ThemeEditorAction.Export -> {
                val theme = ui.value.theme
                eventChannel.trySend(ThemeEditorEvent.ExportJson("${theme.name}.json", ThemeCodec.encode(theme)))
            }

            is ThemeEditorAction.PickFont -> {
                eventChannel.trySend(ThemeEditorEvent.PickFont(action.role))
            }

            ThemeEditorAction.Duplicate -> {
                ui.value = ThemeEditorReducer.start(ThemeEditorReducer.copyOf(ui.value.theme), savedId = null)
            }

            ThemeEditorAction.Use -> {
                use()
            }

            ThemeEditorAction.Back -> {
                eventChannel.trySend(ThemeEditorEvent.Close)
            }

            else -> {
                ui.update { ThemeEditorReducer.reduce(it, action) }
            }
        }
    }

    /** The import picker answered with [text] from a file called [fileName]. */
    fun onImported(
        fileName: String,
        text: String?,
    ) {
        ui.update {
            ThemeEditorReducer.imported(it, fileName, text ?: UNREADABLE)
        }
    }

    /** The font picker chose [family] for [role]. */
    fun onFontChosen(
        role: FontRole,
        family: String,
    ) = onAction(ThemeEditorAction.SetFont(role, family))

    private fun use() {
        val state = ui.value
        val theme = state.theme
        if (!library.save(theme)) {
            ui.update { it.copy(saveFailed = true) }
            return
        }
        // A rename gives the theme a new id; the old file must not linger as a twin.
        state.savedId?.takeIf { it != theme.id }?.let(library::delete)
        settings.update { it.copy(previewThemeId = theme.id) }
        ui.update { it.copy(savedId = theme.id, dirty = false, saveFailed = false) }
        eventChannel.trySend(ThemeEditorEvent.Close)
    }

    private companion object {
        /** Not JSON: the codec's own "the JSON is broken" message names the file, as every import error does. */
        const val UNREADABLE = ""
    }
}
