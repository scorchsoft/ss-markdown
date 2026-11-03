package com.scorchsoft.ssmarkdown.media

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.nav.AppScreen
import com.scorchsoft.ssmarkdown.preview.bridge.MediaKind
import com.scorchsoft.ssmarkdown.settings.DiagramFormat
import com.scorchsoft.ssmarkdown.settings.ExportDestination
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The bytes of a picture written in the Markdown as [source]; the app is offline, so local ones only. */
fun interface PictureSource {
    /** The picture's bytes, or null when it is remote, missing or outside the granted folder. */
    fun bytes(source: String): ByteArray?
}

/** Draws one image for the viewer, the sheet or an export. */
fun interface MediaLoader {
    /** [item] as PNG at [scale] (pictures: their own bytes), or as SVG when [svg]; null if it could not be drawn. */
    suspend fun bytes(
        item: MediaItem,
        svg: Boolean,
        scale: Int,
    ): ByteArray?
}

/** The Mermaid sheet's rows (ux-spec "Mermaid"). */
enum class MediaMenuAction { ViewFullScreen, SavePng, SaveSvg, CopyImage, Share, ShowSource }

/**
 * Diagrams and pictures: the viewer, the long-press sheet and Export
 * images. The list of images comes from the source ([MediaScanner]); the
 * pixels come from the preview page (diagrams) or the document's folder
 * (pictures), so nothing is fetched from the network.
 */
class MediaController(
    private val vm: EditorViewModel,
) : MediaLoader {
    /** Every image in the document on screen, in order. */
    fun items(): List<MediaItem> = vm.active?.let { MediaScanner.scan(it.text.snapshot()) }.orEmpty()

    /** Gestures rule 2: a tap on a diagram or picture opens the viewer there. Maths has no viewer yet. */
    fun onTap(
        kind: MediaKind,
        line: Int,
    ) {
        if (kind == MediaKind.MATH) return
        val items = items()
        val index = indexAt(items, line) ?: return
        vm.viewer.open(docName(), items, index)
        vm.emit(EditorEvent.Open(AppScreen.Viewer))
    }

    /** A long-press on a diagram or picture: the Mermaid sheet. */
    fun onLongPress(
        kind: MediaKind,
        line: Int,
    ) {
        if (kind == MediaKind.MATH) return
        val items = items()
        val item = indexAt(items, line)?.let(items::get) ?: return
        val scale = vm.deps.settings.settings.value.export.scale
        vm.update { it.copy(overlay = EditorOverlay.MediaMenu(kind, item.line, item.title, scale)) }
    }

    /** A row of the Mermaid sheet. */
    fun onMenu(
        action: MediaMenuAction,
        menu: EditorOverlay.MediaMenu,
    ) {
        vm.update { it.copy(overlay = null) }
        val item = items().firstOrNull { it.line == menu.line } ?: return
        when (action) {
            MediaMenuAction.ViewFullScreen -> onTap(menu.kind, menu.line)
            MediaMenuAction.ShowSource -> showSource(item.line)
            else -> deliver(item, action)
        }
    }

    override suspend fun bytes(
        item: MediaItem,
        svg: Boolean,
        scale: Int,
    ): ByteArray? =
        when (item.type) {
            MediaType.Diagram -> {
                vm.preview.exports.request(item.line, png = !svg, scale = scale)
            }

            MediaType.Picture -> {
                item.source?.let { source ->
                    withContext(vm.deps.io) { vm.deps.pictures.bytes(source) }
                }
            }
        }

    private fun deliver(
        item: MediaItem,
        action: MediaMenuAction,
    ) {
        ensurePreview()
        val svg = action == MediaMenuAction.SaveSvg
        val scale = vm.deps.settings.settings.value.export.scale
        val document = vm.active
        vm.deps.scope.launch {
            val bytes = bytes(item, svg, scale)
            if (vm.active !== document) {
                vm.message(MessageKind.ExportStopped)
                return@launch
            }
            if (bytes == null) {
                vm.message(MessageKind.ImagesExportFailed)
                return@launch
            }
            val (ext, mime) = ExportNames.typeOf(item, svg)
            val file = ExportFile(ExportNames.slug(item.title) + "." + ext, mime, bytes)
            when (action) {
                MediaMenuAction.CopyImage -> vm.emit(EditorEvent.CopyImage(file))
                MediaMenuAction.Share -> vm.emit(EditorEvent.ShareFiles(listOf(file)))
                else -> vm.emit(EditorEvent.SaveToDownloads(docName(), listOf(file)))
            }
        }
    }

    private fun showSource(line: Int) {
        vm.onAction(EditorAction.SelectMode(ViewMode.Code))
        vm.jumpToLine(line)
    }

    /** Diagrams are drawn by the page, so it must be on screen: Code switches to Preview first. */
    internal fun ensurePreview() {
        if (vm.state.mode == ViewMode.Code) vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
    }

    internal fun docName(): String = ExportNames.docStem(vm.state.title)

    private companion object {
        fun indexAt(
            items: List<MediaItem>,
            line: Int,
        ): Int? {
            val exact = items.indexOfFirst { it.line == line }
            if (exact >= 0) return exact
            // The page reports the block's first line; a picture mid-paragraph sits a line or two later.
            return items.indexOfFirst { it.line >= line }.takeIf { it >= 0 } ?: items.lastIndex.takeIf { it >= 0 }
        }
    }
}
