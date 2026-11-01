package com.scorchsoft.ssmarkdown.media

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An image's pixels as loaded, or why there are none. */
sealed interface ViewerImage {
    /** Still drawing. */
    data object Loading : ViewerImage

    /** Encoded image bytes (PNG for diagrams, the picture's own format otherwise). */
    class Loaded(
        val bytes: ByteArray,
    ) : ViewerImage

    /** It could not be drawn or read: the viewer says so instead of showing nothing. */
    data object Failed : ViewerImage
}

/**
 * The full-screen viewer (ux-spec "Viewer"; mockup Viewer).
 *
 * @property docName the document's name, for saved files' folder.
 * @property items every image in the document: the filmstrip.
 * @property index the one on screen.
 * @property images what has loaded so far, by index.
 */
@Immutable
data class ViewerUiState(
    val docName: String = "",
    val items: List<MediaItem> = emptyList(),
    val index: Int = 0,
    val images: Map<Int, ViewerImage> = emptyMap(),
) {
    /** The image on screen, if any. */
    val current: MediaItem? get() = items.getOrNull(index)
}

/** What the viewer can do. */
sealed interface ViewerAction {
    /** Swipe or a filmstrip tap. */
    data class Select(
        val index: Int,
    ) : ViewerAction

    data object Save : ViewerAction

    data object Share : ViewerAction

    data object Back : ViewerAction
}

/**
 * The viewer's state. Images load once each, the one on screen first,
 * then the rest of the filmstrip in document order. Diagrams load as 3×
 * PNG so they stay sharp when zoomed.
 */
class ViewerViewModel(
    private val loader: MediaLoader,
    private val scope: CoroutineScope,
    private val emit: (EditorEvent) -> Unit,
) {
    private val ui = MutableStateFlow(ViewerUiState())
    private var loading: Job? = null

    /** The screen state. */
    val state: StateFlow<ViewerUiState> = ui.asStateFlow()

    /** Shows [items] from [index]. */
    fun open(
        docName: String,
        items: List<MediaItem>,
        index: Int,
    ) {
        loading?.cancel()
        ui.value = ViewerUiState(docName, items, index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        loading = scope.launch { loadAll() }
    }

    /** A viewer action. */
    fun onAction(action: ViewerAction) {
        when (action) {
            is ViewerAction.Select -> ui.update { it.copy(index = action.index.coerceIn(0, it.items.lastIndex)) }

            ViewerAction.Save -> current()?.let { emit(EditorEvent.SaveToDownloads(ui.value.docName, listOf(it))) }

            ViewerAction.Share -> current()?.let { emit(EditorEvent.ShareFiles(listOf(it))) }

            // The screen stack is the route's: it pops the viewer itself.
            ViewerAction.Back -> Unit
        }
    }

    private suspend fun loadAll() {
        val start = ui.value.index
        val order =
            listOf(start) +
                ui.value.items.indices
                    .filter { it != start }
        for (index in order) {
            val item = ui.value.items.getOrNull(index) ?: continue
            ui.update { it.copy(images = it.images + (index to ViewerImage.Loading)) }
            val bytes = loader.bytes(item, svg = false, scale = VIEWER_SCALE)
            val image = bytes?.let(ViewerImage::Loaded) ?: ViewerImage.Failed
            ui.update { it.copy(images = it.images + (index to image)) }
        }
    }

    private fun current(): ExportFile? {
        val state = ui.value
        val item = state.current
        val image = state.images[state.index] as? ViewerImage.Loaded
        if (item == null || image == null) return null
        val (ext, mime) = ExportNames.typeOf(item, svg = false)
        return ExportFile(ExportNames.slug(item.title) + "." + ext, mime, image.bytes)
    }

    private companion object {
        const val VIEWER_SCALE = 3
    }
}
