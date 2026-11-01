package com.scorchsoft.ssmarkdown.media

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.settings.DiagramFormat
import com.scorchsoft.ssmarkdown.settings.ExportDefaults
import com.scorchsoft.ssmarkdown.settings.ExportDestination

/**
 * The Export images sheet (ux-spec "Export images"; mockup ExportImages).
 *
 * @property docName the document's name without extension: the folder and ZIP name.
 * @property items every image in the file, in order; [selected] holds the indices chosen.
 * @property running true while the images are being drawn and written.
 */
@Immutable
data class ExportImagesState(
    val docName: String,
    val items: List<MediaItem>,
    val selected: Set<Int> = items.indices.toSet(),
    val format: DiagramFormat = DiagramFormat.Png,
    val scale: Int = 2,
    val destination: ExportDestination = ExportDestination.SeparateFiles,
    val running: Boolean = false,
) {
    /** How many diagrams the file has. */
    val diagrams: Int get() = items.count { it.type == MediaType.Diagram }

    /** How many pictures the file has. */
    val pictures: Int get() = items.size - diagrams

    /** The chosen images, in document order. */
    val chosen: List<MediaItem> get() = items.filterIndexed { index, _ -> index in selected }

    /** What "Export n images" counts. */
    val count: Int get() = selected.size

    /** The choices to remember for next time. */
    fun defaults(zipFolder: String?): ExportDefaults = ExportDefaults(format, scale, destination, zipFolder)

    /** Building the sheet. */
    companion object {
        /** A fresh sheet for [items], with everything selected and last time's choices. */
        fun of(
            docName: String,
            items: List<MediaItem>,
            defaults: ExportDefaults,
        ): ExportImagesState =
            ExportImagesState(
                docName = docName,
                items = items,
                format = defaults.format,
                scale = defaults.scale.coerceIn(1, MAX_SCALE),
                destination = defaults.destination,
            )

        /** The largest PNG scale offered. */
        const val MAX_SCALE: Int = 3
    }
}

/** What the Export images sheet can do. */
sealed interface ExportImagesAction {
    /** Ticks or unticks a thumbnail. */
    data class Toggle(
        val index: Int,
    ) : ExportImagesAction

    /** "Select none", or "Select all" once nothing is selected. */
    data object ToggleAll : ExportImagesAction

    data class SetFormat(
        val format: DiagramFormat,
    ) : ExportImagesAction

    data class SetScale(
        val scale: Int,
    ) : ExportImagesAction

    data class SetDestination(
        val destination: ExportDestination,
    ) : ExportImagesAction

    /** "Export n images". */
    data object Export : ExportImagesAction

    /** "Share instead": the system share sheet with the files. */
    data object Share : ExportImagesAction

    data object Dismiss : ExportImagesAction
}

/** The sheet's pure transitions. */
object ExportImagesReducer {
    /** [state] after [action]; Export, Share and Dismiss are effects and leave it unchanged. */
    fun reduce(
        state: ExportImagesState,
        action: ExportImagesAction,
    ): ExportImagesState =
        when (action) {
            is ExportImagesAction.Toggle -> {
                if (action.index !in state.items.indices) {
                    state
                } else if (action.index in state.selected) {
                    state.copy(selected = state.selected - action.index)
                } else {
                    state.copy(selected = state.selected + action.index)
                }
            }

            ExportImagesAction.ToggleAll -> {
                state.copy(selected = if (state.selected.isEmpty()) state.items.indices.toSet() else emptySet())
            }

            is ExportImagesAction.SetFormat -> {
                state.copy(format = action.format)
            }

            is ExportImagesAction.SetScale -> {
                state.copy(scale = action.scale.coerceIn(1, ExportImagesState.MAX_SCALE))
            }

            is ExportImagesAction.SetDestination -> {
                state.copy(destination = action.destination)
            }

            else -> {
                state
            }
        }
}

/** One file an export writes. */
class ExportFile(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
)

/**
 * File names for an export, numbered in document order so a folder sorts
 * the way the document reads: `01-save-flow.png`, `02-architecture.svg`.
 */
object ExportNames {
    private val unsafe = Regex("[^a-z0-9]+")
    private const val MAX_STEM = 40

    /** A file-name stem from [title]: lower case, dashes, no path characters. */
    fun slug(title: String): String =
        unsafe
            .replace(title.lowercase(), "-")
            .trim('-')
            .take(MAX_STEM)
            .trimEnd('-')
            .ifEmpty { "image" }

    /** The [position]th (1-based) file of [count], for [title], with [extension]. */
    fun name(
        position: Int,
        count: Int,
        title: String,
        extension: String,
    ): String {
        val width = maxOf(2, count.toString().length)
        return position.toString().padStart(width, '0') + "-" + slug(title) + "." + extension
    }

    /** File extension and MIME type: diagrams by rendition, pictures by their own extension. */
    fun typeOf(
        item: MediaItem,
        svg: Boolean,
    ): Pair<String, String> {
        if (item.type == MediaType.Diagram) return if (svg) "svg" to "image/svg+xml" else "png" to "image/png"
        val ext =
            item.source
                ?.substringBefore('?')
                ?.substringAfterLast('.', "")
                ?.lowercase()
                .orEmpty()
        return when (ext) {
            "jpg", "jpeg" -> "jpg" to "image/jpeg"
            "gif" -> "gif" to "image/gif"
            "webp" -> "webp" to "image/webp"
            "svg" -> "svg" to "image/svg+xml"
            else -> "png" to "image/png"
        }
    }

    /** The document's name without its extension, for the folder and the ZIP. */
    fun docStem(fileName: String): String = fileName.substringBeforeLast('.').ifEmpty { "untitled" }
}
