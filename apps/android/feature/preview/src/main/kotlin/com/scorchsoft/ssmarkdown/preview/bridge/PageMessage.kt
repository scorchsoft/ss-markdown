package com.scorchsoft.ssmarkdown.preview.bridge

import com.scorchsoft.ssmarkdown.preview.PreviewListener
import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import java.util.Base64

/** A splice of the source in UTF-16 offsets: `[from, to)` becomes [insert]. */
data class TextEdit(
    val from: Int,
    val to: Int,
    val insert: String,
)

/**
 * A format asked for in Preview: [from] and [to] are source offsets the page's
 * source map gave for the selection, in the page's text at [baseRev].
 */
data class PreviewFormatRequest(
    val action: FormatAction,
    val from: Int,
    val to: Int,
    val baseRev: Int,
    val inTable: Boolean,
    /** The load it was made in; see [PageMessage.UNSTAMPED]. */
    val epoch: Int = PageMessage.UNSTAMPED,
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

    /** Constants of the protocol. */
    companion object {
        /**
         * The `epoch` of a message from a page that did not stamp one. Every
         * message that carries offsets (edits, selections, format requests,
         * resyncs) is stamped with the epoch of the [HostMessage.Load] the page
         * was showing, so the host can tell an edit typed into the previous
         * document from one typed into this one (DS-13).
         */
        const val UNSTAMPED: Int = -1
    }

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
     * all relative to the page's text: revision [baseRev] plus the page's own
     * earlier edits. Changes the host made meanwhile may not be in it, so
     * never apply these directly: `PreviewEditor.onPageEdits` rebases them.
     */
    data class SourceEdit(
        val baseRev: Int,
        val edits: List<TextEdit>,
        val epoch: Int = UNSTAMPED,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onSourceEdits(baseRev, edits, epoch)
    }

    /**
     * The selection while editing, so the strip can show B / I state. Offsets
     * are in the text at [baseRev] (the page only reports with nothing in flight).
     */
    data class Selection(
        val from: Int,
        val to: Int,
        val marks: Set<String>,
        val inTable: Boolean,
        val baseRev: Int,
    ) : PageMessage {
        // Not routed by epoch: a stale selection only colours the strip until the next one.
        override fun deliverTo(listener: PreviewListener) = listener.onSelection(from, to, marks, inTable, baseRev)
    }

    /**
     * A strip key or keyboard shortcut in Preview, with the selection's source
     * range; `PreviewEditor` applies it in the document's style.
     */
    data class FormatRequest(
        val request: PreviewFormatRequest,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onFormatRequest(request)
    }

    /** The page fell out of step; answer with a fresh load of the host's text. */
    data class Resync(
        val reason: String,
        val epoch: Int = UNSTAMPED,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onResync(reason, epoch)
    }

    /** A change Preview could not write back was undone on screen; suggest Code for [line]. */
    data class EditRefused(
        val line: Int,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onEditRefused(line)
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
        val epoch: Int = UNSTAMPED,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) =
            listener.onSourceEdits(baseRev, listOf(TextEdit(offset, offset + 1, if (checked) "x" else " ")), epoch)
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

    /**
     * The answer to [HostMessage.ExportHtml]: the rendered lines as clean
     * HTML, or null when the page could not render them. Consumed by
     * `PreviewView.exportHtml`, not by the listener.
     */
    data class HtmlResult(
        val requestId: Int,
        val html: String?,
    ) : PageMessage

    data class Error(
        val message: String,
    ) : PageMessage {
        override fun deliverTo(listener: PreviewListener) = listener.onPageError(message)
    }
}
