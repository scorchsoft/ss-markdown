package com.scorchsoft.ssmarkdown.fontpicker

import com.scorchsoft.ssmarkdown.fonts.FontCategory
import com.scorchsoft.ssmarkdown.fonts.FontRepository
import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFonts
import com.scorchsoft.ssmarkdown.fonts.download.DownloadResult
import com.scorchsoft.ssmarkdown.fonts.importing.ImportResult
import com.scorchsoft.ssmarkdown.fonts.store.InstalledSource
import com.scorchsoft.ssmarkdown.themes.FontRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * The font picker's view model over `feature/fonts`' [FontRepository]:
 * bundled and installed families first, then the Google Fonts catalogue.
 * A download is the only network use in the app, happens only on "Get",
 * shows its progress and can be cancelled; a failure says what is used
 * meanwhile (mockup MoreStates).
 *
 * @param fonts built lazily: the catalogue is parsed on first use, off the main thread.
 * @param onChosen where a chosen family goes (the theme editor).
 */
class FontPickerViewModel(
    private val fonts: () -> FontRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val onChosen: (FontRole, String) -> Unit,
) {
    private val ui = MutableStateFlow(FontPickerUiState())
    private val eventChannel = Channel<FontPickerEvent>(Channel.BUFFERED)
    private val jobs = HashMap<String, Job>()

    /** The screen state. */
    val state: StateFlow<FontPickerUiState> = ui.asStateFlow()

    /** One-shot effects. */
    val events: Flow<FontPickerEvent> = eventChannel.receiveAsFlow()

    /** Opens the picker for [role] of [themeName], whose font is [current] now. */
    fun open(
        role: FontRole,
        themeName: String,
        current: String,
    ) {
        ui.value = FontPickerUiState(role = role, themeName = themeName, current = current)
        refresh()
    }

    /** A picker action. */
    fun onAction(action: FontPickerAction) {
        when (action) {
            is FontPickerAction.Search -> ui.update { it.copy(query = action.query) }.also { refresh() }
            is FontPickerAction.Filter -> ui.update { it.copy(category = action.category) }.also { refresh() }
            is FontPickerAction.Choose -> choose(action.family)
            is FontPickerAction.Download -> download(action.family)
            is FontPickerAction.Cancel -> jobs.remove(action.family)?.cancel()
            FontPickerAction.Import -> eventChannel.trySend(FontPickerEvent.PickFile)
            FontPickerAction.DismissProblem -> ui.update { it.copy(problem = null) }
            FontPickerAction.Back -> eventChannel.trySend(FontPickerEvent.Close)
        }
    }

    /** The file picker answered: import [fileName] (null when nothing was picked). */
    fun onFilePicked(
        fileName: String?,
        open: () -> InputStream,
    ) {
        fileName ?: return
        scope.launch {
            when (val result = withContext(io) { fonts().import(fileName, open) }) {
                is ImportResult.Imported -> choose(result.font.family)
                is ImportResult.Failure -> ui.update { it.copy(problem = result.message()) }
            }
        }
    }

    private fun choose(family: String) {
        onChosen(ui.value.role, family)
        eventChannel.trySend(FontPickerEvent.Close)
    }

    private fun download(family: String) {
        if (family in jobs) return
        ui.update { it.copy(downloading = it.downloading + (family to 0f), problem = null) }
        val job =
            scope.launch {
                try {
                    val result =
                        withContext(io) {
                            fonts().download(family) { p ->
                                val fraction = if (p.filesTotal == 0) 0f else p.filesDone.toFloat() / p.filesTotal
                                ui.update { it.copy(downloading = it.downloading + (family to fraction)) }
                            }
                        }
                    onDownloaded(result)
                } finally {
                    jobs.remove(family)
                    ui.update { it.copy(downloading = it.downloading - family) }
                }
            }
        jobs[family] = job
    }

    private fun onDownloaded(result: DownloadResult) {
        when (result) {
            is DownloadResult.Done, is DownloadResult.AlreadyBundled -> {
                refresh()
                choose(result.family)
            }

            is DownloadResult.Failure -> {
                ui.update { it.copy(problem = result.message()) }
            }
        }
    }

    private fun refresh() {
        val query = ui.value.query
        val category = ui.value.category
        scope.launch {
            val (phone, google) = withContext(io) { lists(fonts(), query, category) }
            ui.update { it.copy(onPhone = phone, google = google) }
        }
    }

    /** Builds both lists; pure apart from reading the repository. */
    internal companion object {
        fun lists(
            repository: FontRepository,
            query: String,
            category: FontCategory?,
        ): Pair<List<FontOption>, List<FontOption>> {
            val needle = query.trim().lowercase()

            fun wanted(
                family: String,
                cat: FontCategory?,
            ) = (category == null || cat == category) && (needle.isEmpty() || needle in family.lowercase())
            val bundled =
                BundledFonts.ALL
                    .filter { repository.resolve(it.family) is com.scorchsoft.ssmarkdown.fonts.FontResolution.Bundled }
                    .map { FontOption(it.family, it.category, FontOrigin.BuiltIn) }
            val installed =
                repository.installed().map { font ->
                    val origin =
                        if (font.source ==
                            InstalledSource.IMPORTED
                        ) {
                            FontOrigin.Imported
                        } else {
                            FontOrigin.Downloaded
                        }
                    FontOption(font.family, repository.catalogue.find(font.family)?.category, origin, font.files.size)
                }
            val phone = (bundled + installed).filter { wanted(it.family, it.category) }
            val onPhoneNames = (bundled + installed).map { it.family.lowercase() }.toSet()
            val google =
                repository.catalogue
                    .search(query, category)
                    .filter { it.family.lowercase() !in onPhoneNames }
                    .map { FontOption(it.family, it.category, FontOrigin.GoogleFonts, it.styles.size, it.approxKb) }
            return phone to google
        }
    }
}
