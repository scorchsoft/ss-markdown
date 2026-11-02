package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.markdown.edit.FormatState
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.RendererState
import com.scorchsoft.ssmarkdown.preview.bridge.LinkTarget
import com.scorchsoft.ssmarkdown.preview.bridge.MediaKind

/**
 * What the preview tells the editor, already checked against the
 * document (the page's messages are data, never instructions). Lines are
 * source lines in the buffer as it is now.
 */
sealed interface PreviewEvent {
    /** Gestures rule 3: a link was tapped. */
    data class LinkTapped(
        val target: LinkTarget,
        val line: Int,
    ) : PreviewEvent

    /** Gestures rule 6: the section menu for the heading at [line]. */
    data class HeadingLongPress(
        val line: Int,
    ) : PreviewEvent

    /** The "Edit in Code" chip on a block Preview cannot edit. */
    data class EditInCode(
        val line: Int,
    ) : PreviewEvent

    /** Gestures rule 2: a diagram, picture or maths block was tapped. */
    data class OpenViewer(
        val kind: MediaKind,
        val line: Int,
    ) : PreviewEvent

    /** A long-press on a diagram or picture: the Mermaid sheet. */
    data class MediaLongPress(
        val kind: MediaKind,
        val line: Int,
    ) : PreviewEvent

    /** The table toolbar's ⋯ for the table at [line]. */
    data class TableMenu(
        val line: Int,
    ) : PreviewEvent

    /** The renderer restarted or paused. */
    data class Renderer(
        val state: RendererState,
    ) : PreviewEvent

    /** Editing in Preview began or ended (the strip's Done key, the reading guard). */
    data class Editing(
        val editing: Boolean,
    ) : PreviewEvent

    /** The formatting under the page's selection, for the strip's pressed keys. */
    data class Format(
        val state: FormatState,
        val inTable: Boolean,
    ) : PreviewEvent

    /** The strip's Link key in Preview: the prompt opens on the page's [selection], in host offsets. */
    data class LinkPrompt(
        val selection: TextSelection,
    ) : PreviewEvent

    /** A change Preview could not write back was undone on screen. */
    data class EditRefused(
        val line: Int,
    ) : PreviewEvent

    /**
     * A Preview edit was applied while the app is paused, after `onPause`
     * made the journal durable: make it durable too (DS-14).
     */
    data object EditedWhilePaused : PreviewEvent

    /** Keyboard undo or redo inside Preview: the host's history. */
    data class History(
        val redo: Boolean,
    ) : PreviewEvent
}

/** The editor, as the preview session sees it. */
interface PreviewHost {
    /** The anchor: the source line the preview opens at. */
    val anchorLine: Int

    /** Whether the open document refuses edits (Preview then stays read-only too). */
    val readOnly: Boolean

    /** The preview's top line changed. */
    fun onPreviewTopLine(line: Int)

    /** The page's scroll position over its scrollable range, 0 to 1, for the reading line. */
    fun onPreviewScrollFraction(fraction: Float) = Unit

    /** Everything else the preview reports. */
    fun onPreviewEvent(event: PreviewEvent)
}
