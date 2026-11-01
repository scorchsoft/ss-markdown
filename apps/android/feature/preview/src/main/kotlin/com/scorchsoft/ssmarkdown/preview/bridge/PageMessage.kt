package com.scorchsoft.ssmarkdown.preview.bridge

import com.scorchsoft.ssmarkdown.preview.PreviewListener
import java.util.Base64

/** A splice of the source in UTF-16 offsets: `[from, to)` becomes [insert]. */
data class TextEdit(
    val from: Int,
    val to: Int,
    val insert: String,
)

/** One heading in the breadcrumb ("where am I?"). */
data class Crumb(
    val level: Int,
    val text: String,
    val line: Int,
)

/** What a tap or long-press in the viewer family landed on. */
enum class MediaKind {
    DIAGRAM,
    IMAGE,
    MATH,
}

/**
 * Messages from the page to Kotlin. They are data, never instructions: the
 * page renders documents that may be hostile, so every value is validated by
 * whoever acts on it (offsets against the buffer, links by [LinkPolicy]).
 *
 * Each message delivers itself to a [PreviewListener]; the few the host view
 * consumes itself (ready, print) deliver nothing.
 */
sealed interface PageMessage {
    /** Hands this message to [listener]. */
    fun deliverTo(listener: PreviewListener) = Unit

    /** The bridge is connected; [version] must equal [PROTOCOL_VERSION]. */
    data class Ready(
        val version: Int,
    ) : PageMessage

    data class Rendered(
        val rev: Int,
        val sections: Int,
        val millis: Int,
    ) : PageMessage

    /** The source line at the top of the viewport, for anchors and the breadcrumb. */
    data class TopLine(
        val line: Int,
        val crumbs: List<Crumb>,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onTopLine(line, crumbs)
    }

    /**
     * Edits made in Preview. They do not overlap, are sorted by offset and are
     * all relative to the document at [baseRev]: apply them from the last to
     * the first. The document is then at `baseRev + 1`.
     */
    data class SourceEdit(
        val baseRev: Int,
        val edits: List<TextEdit>,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onSourceEdits(baseRev, edits)
    }

    /** The selection while editing, so the strip can show B / I state. */
    data class Selection(
        val from: Int,
        val to: Int,
        val marks: Set<String>,
        val inTable: Boolean,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onSelection(from, to, marks, inTable)
    }

    /** A formatting shortcut pressed in Preview; Kotlin applies it in the document's style. */
    data class FormatRequest(
        val action: String,
        val from: Int,
        val to: Int,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onFormatRequest(action, from, to)
    }

    data class EditingChanged(
        val editing: Boolean,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onEditingChanged(editing)
    }

    /** A task checkbox: the character at [offset] becomes `x` when [checked], else a space. */
    data class TaskToggle(
        val baseRev: Int,
        val offset: Int,
        val checked: Boolean,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) =
            listener.onSourceEdits(
                baseRev,
                listOf(
                    TextEdit(
                        offset,
                        offset + 1,
                        if (checked) "x" else " ",
                    ),
                ),
            )
    }

    data class LinkTapped(
        val href: String,
        val line: Int,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onLinkTapped(LinkPolicy.classify(href), line)
    }

    data class HeadingLongPress(
        val line: Int,
        val level: Int,
        val text: String,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onHeadingLongPress(line, level, text)
    }

    data class EditInCode(
        val line: Int,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onEditInCode(line)
    }

    data class OpenViewer(
        val kind: MediaKind,
        val index: Int,
        val line: Int,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onOpenViewer(kind, index, line)
    }

    data class MediaLongPress(
        val kind: MediaKind,
        val index: Int,
        val line: Int,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onMediaLongPress(kind, index, line)
    }

    data class TableMenu(
        val line: Int,
        val row: Int,
        val column: Int,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onTableMenu(line, row, column)
    }

    /** An export: [data] is SVG text, or base64 PNG bytes when [mime] is `image/png`. */
    data class ExportResult(
        val requestId: Int,
        val mime: String,
        val data: String,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onExportResult(requestId, mime, bytes())

        /** The payload as bytes: PNG decoded from base64, SVG as UTF-8 text. */
        fun bytes(): ByteArray = if (mime == "image/png") Base64.getDecoder().decode(data) else data.toByteArray()
    }

    data object PrintReady : PageMessage

    data class Error(
        val message: String,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onPageError(message)
    }
}
