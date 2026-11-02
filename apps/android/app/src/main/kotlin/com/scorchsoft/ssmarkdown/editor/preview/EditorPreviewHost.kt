package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorViewModel
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.StripController
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction
import com.scorchsoft.ssmarkdown.preview.RendererState
import com.scorchsoft.ssmarkdown.preview.bridge.LinkTarget

/**
 * The editor as the [PreviewSession] sees it: the anchor to open at, and
 * where each preview event goes. Every event maps to an existing editor
 * path, so Preview and Code share one section menu, one viewer, one undo
 * history and one message bar.
 */
@Suppress("TooManyFunctions") // One small function per kind of preview report.
class EditorPreviewHost(
    private val vm: EditorViewModel,
) : PreviewHost {
    override val anchorLine: Int get() = vm.anchorLine.value

    override val readOnly: Boolean get() = vm.active?.open?.readOnly != null

    override fun onPreviewTopLine(line: Int) {
        // Drawing an export scrolls the page to the diagram; that is not the reader moving.
        if (vm.preview.exports.busy) return
        vm.onPaneTopLine(Pane.Preview, line)
    }

    override fun onPreviewScrollFraction(fraction: Float) {
        if (vm.preview.exports.busy) return
        vm.onPaneScrollFraction(Pane.Preview, fraction)
    }

    @Suppress("CyclomaticComplexMethod") // One branch per event.
    override fun onPreviewEvent(event: PreviewEvent) {
        when (event) {
            is PreviewEvent.LinkTapped -> {
                onLink(event.target, event.line)
            }

            is PreviewEvent.HeadingLongPress -> {
                vm.onAction(
                    EditorAction.Navigator(NavigatorAction.HeadingMenu(event.line)),
                )
            }

            is PreviewEvent.EditInCode -> {
                showInCode(event.line)
            }

            is PreviewEvent.OpenViewer -> {
                vm.media.onTap(event.kind, event.line)
            }

            is PreviewEvent.MediaLongPress -> {
                vm.media.onLongPress(event.kind, event.line)
            }

            is PreviewEvent.TableMenu -> {
                vm.update { it.copy(overlay = EditorOverlay.TableMenu(event.line)) }
            }

            is PreviewEvent.Renderer -> {
                onRenderer(event.state)
            }

            is PreviewEvent.Editing -> {
                vm.update { it.copy(preview = it.preview.copy(editing = event.editing)) }
            }

            is PreviewEvent.Format -> {
                onFormat(event)
            }

            is PreviewEvent.EditRefused -> {
                vm.message(MessageKind.EditRefused)
            }

            is PreviewEvent.History -> {
                onHistory(event.redo)
            }

            PreviewEvent.EditedWhilePaused -> {
                vm.persistNow()
            }
        }
    }

    /** The link chip's Open, or a tap on a link inside the document (a relative one) with "Tap to edit" off. */
    fun open(target: LinkTarget) {
        when (target) {
            is LinkTarget.External -> vm.emit(EditorEvent.OpenLink(target.url))
            is LinkTarget.Relative -> openRelative(target.path)
            LinkTarget.Blocked -> vm.message(MessageKind.LinkBlocked)
        }
    }

    /** A button on the link [chip]: Open, Copy link (nothing is opened) or Edit link. */
    fun onChip(
        action: EditorAction,
        chip: EditorOverlay.LinkChip,
    ) {
        when (action) {
            EditorAction.OpenLink -> {
                open(chip.target)
            }

            EditorAction.CopyLink -> {
                val target = chip.target as? LinkTarget.External ?: return
                vm.emit(EditorEvent.CopyText(target.url))
                vm.message(MessageKind.LinkCopied)
            }

            EditorAction.EditLink -> {
                showInCode(chip.line)
            }

            else -> {
                Unit
            }
        }
    }

    /** Switches to Code with [line] at the top: "Edit in Code", "Edit link", "Show source". */
    fun showInCode(line: Int) {
        vm.onAction(EditorAction.SelectMode(ViewMode.Code))
        vm.jumpToLine(line)
    }

    private fun onLink(
        target: LinkTarget,
        line: Int,
    ) {
        val canEdit = vm.deps.settings.settings.value.tapToEditPreview
        // An external link always asks first (SEC-7): one tap on rendered text never launches another app.
        if (canEdit || target is LinkTarget.External) {
            vm.update { it.copy(overlay = EditorOverlay.LinkChip(target, line, canEdit)) }
        } else {
            open(target)
        }
    }

    /** A link to another file next to this one: opened when the open folder has it, by name. */
    private fun openRelative(path: String) {
        val name = path.substringBefore('#').substringAfterLast('/')
        val row =
            vm.state.folder
                ?.rows
                ?.firstOrNull { !it.isDirectory && it.name == name }
        if (row != null) vm.openDocument(row.uri) else vm.message(MessageKind.LinkBlocked)
    }

    private fun onRenderer(state: RendererState) {
        vm.update { it.copy(preview = it.preview.copy(renderer = state)) }
        if (state == RendererState.RESTARTED) vm.message(MessageKind.PreviewRestarted)
    }

    private fun onFormat(event: PreviewEvent.Format) {
        val pressed = StripController.pressedKeys(event.state)
        vm.update {
            it.copy(
                strip =
                    it.strip.copy(
                        pressed = pressed,
                        inTable = event.inTable,
                        block = PreviewStripKeys.choiceFor(event.state.block),
                    ),
                preview = it.preview.copy(inHeading = event.state.headingLevel > 0),
            )
        }
    }

    private fun onHistory(redo: Boolean) {
        val surface = vm.active?.surface ?: return
        if (redo) surface.redo() else surface.undo()
        vm.refresh()
    }
}
