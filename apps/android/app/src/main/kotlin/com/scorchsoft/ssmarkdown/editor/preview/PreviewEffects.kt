package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage

/**
 * The side effects of the Preview's actions: the link chip, the Mermaid
 * and table sheets, Export images, PDF, the renderer's Retry and the
 * Display sheet's Preview choices. The editor's own table stays in
 * `EditorEffects`; this is its Preview half.
 */
internal object PreviewEffects {
    @Suppress("CyclomaticComplexMethod") // A dispatch table: one short branch per action.
    fun run(
        vm: EditorViewModel,
        action: EditorAction.PreviewAction,
        before: EditorUiState,
    ) {
        when (action) {
            is EditorAction.PaneTouched -> {
                vm.scrollLink.touched(action.pane)
            }

            EditorAction.OpenLink, EditorAction.CopyLink, EditorAction.EditLink -> {
                (before.overlay as? EditorOverlay.LinkChip)?.let { vm.previewHost.onChip(action, it) }
            }

            is EditorAction.MediaMenu -> {
                (before.overlay as? EditorOverlay.MediaMenu)?.let {
                    vm.media.onMenu(
                        action.action,
                        it,
                    )
                }
            }

            is EditorAction.ExportImages -> {
                vm.imageExports.onExport(action.action)
            }

            is EditorAction.Table -> {
                vm.preview.send(HostMessage.Table(action.op))
            }

            EditorAction.RetryPreview -> {
                vm.preview.view?.retry()
            }

            is EditorAction.SetPreviewWrap -> {
                vm.deps.settings.update { it.copy(previewWrap = action.wrap) }
            }

            is EditorAction.SetPreviewTheme -> {
                vm.deps.settings.update { it.copy(previewThemeId = action.id) }
            }

            is EditorAction.EditTheme -> {
                vm.emit(EditorEvent.EditTheme(action.id))
            }
        }
        if (action in chipActions || action is EditorAction.Table) {
            vm.update { it.copy(overlay = null) }
        }
    }

    /** ⋮ → Export PDF: the page renders everything, then the system print dialog opens (A4, or Letter in the US). */
    fun exportPdf(vm: EditorViewModel) {
        if (vm.state.mode == ViewMode.Code) vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        val name =
            vm.state.title
                .substringBeforeLast('.')
                .ifEmpty { "Untitled" }
        vm.emit(EditorEvent.Print(name))
    }

    /** ⋮ → Copy as rich text. */
    fun copyAllRich(vm: EditorViewModel) = vm.saves.copyAll(richText = true)

    /** The link chip's buttons; each closes the chip. */
    private val chipActions = setOf(EditorAction.OpenLink, EditorAction.CopyLink, EditorAction.EditLink)
}
