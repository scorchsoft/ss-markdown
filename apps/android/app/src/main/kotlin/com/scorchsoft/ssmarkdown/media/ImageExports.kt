package com.scorchsoft.ssmarkdown.media

import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.settings.DiagramFormat
import com.scorchsoft.ssmarkdown.settings.ExportDestination
import kotlinx.coroutines.launch

/**
 * ⋮ → Export images… (ux-spec "Export images", J6): the sheet's state,
 * then every chosen image drawn in document order and handed to the
 * Activity to save as separate files in Downloads, as one ZIP, or to
 * share. The choices are remembered for next time.
 */
class ImageExports(
    private val vm: EditorViewModel,
    private val media: MediaController,
) {
    /** ⋮ → Export images… */
    fun openExport() {
        val items = media.items()
        if (items.isEmpty()) {
            vm.message(MessageKind.NothingToExport)
            return
        }
        val defaults = vm.deps.settings.settings.value.export
        vm.update {
            it.copy(
                overlay = EditorOverlay.ExportImages(ExportImagesState.of(media.docName(), items, defaults)),
            )
        }
    }

    /** An Export images sheet action. */
    fun onExport(action: ExportImagesAction) {
        val sheet = (vm.state.overlay as? EditorOverlay.ExportImages)?.state ?: return
        when (action) {
            ExportImagesAction.Dismiss -> vm.update { it.copy(overlay = null) }
            ExportImagesAction.Export -> export(sheet, share = false)
            ExportImagesAction.Share -> export(sheet, share = true)
            else -> setSheet(ExportImagesReducer.reduce(sheet, action))
        }
    }

    /** The files for [items] in [format] at [scale], numbered in document order. */
    suspend fun files(
        items: List<MediaItem>,
        format: DiagramFormat,
        scale: Int,
    ): List<ExportFile> {
        val files = ArrayList<ExportFile>()
        items.forEachIndexed { index, item ->
            for (svg in renditions(item, format)) {
                val bytes = media.bytes(item, svg, scale) ?: continue
                val (ext, mime) = ExportNames.typeOf(item, svg)
                files += ExportFile(ExportNames.name(index + 1, items.size, item.title, ext), mime, bytes)
            }
        }
        return files
    }

    private fun export(
        sheet: ExportImagesState,
        share: Boolean,
    ) {
        if (sheet.running || sheet.count == 0) return
        setSheet(sheet.copy(running = true))
        media.ensurePreview()
        vm.deps.scope.launch {
            val files = files(sheet.chosen, sheet.format, sheet.scale)
            val zipFolder = vm.deps.settings.settings.value.export.zipFolder
            vm.deps.settings.update { it.copy(export = sheet.defaults(zipFolder)) }
            vm.update { it.copy(overlay = null) }
            when {
                files.isEmpty() -> {
                    vm.message(MessageKind.ImagesExportFailed)
                }

                share -> {
                    vm.emit(EditorEvent.ShareFiles(files))
                }

                sheet.destination == ExportDestination.Zip -> {
                    vm.emit(
                        EditorEvent.SaveZip(sheet.docName, files, zipFolder),
                    )
                }

                else -> {
                    vm.emit(EditorEvent.SaveToDownloads(sheet.docName, files))
                }
            }
        }
    }

    private fun setSheet(sheet: ExportImagesState) = vm.update { it.copy(overlay = EditorOverlay.ExportImages(sheet)) }

    private companion object {
        fun renditions(
            item: MediaItem,
            format: DiagramFormat,
        ): List<Boolean> =
            when {
                item.type == MediaType.Picture -> listOf(false)
                format == DiagramFormat.Png -> listOf(false)
                format == DiagramFormat.Svg -> listOf(true)
                else -> listOf(false, true)
            }
    }
}
