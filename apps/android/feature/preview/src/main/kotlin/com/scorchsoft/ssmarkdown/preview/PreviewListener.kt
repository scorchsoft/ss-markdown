package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.Crumb
import com.scorchsoft.ssmarkdown.preview.bridge.LinkTarget
import com.scorchsoft.ssmarkdown.preview.bridge.MediaKind
import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PreviewFormatRequest
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit

/**
 * Everything the preview reports to its host, grouped into editing and content
 * events. Every method has a default, so a host implements only what its
 * screen uses. Offsets and lines are in the document the host last sent; the
 * host validates them before acting.
 */
interface PreviewListener :
    PreviewEditingListener,
    PreviewContentListener {
    fun onTopLine(
        line: Int,
        crumbs: List<Crumb>,
    ) = Unit

    /**
     * The page scrolled: [fraction] is its scroll position over the
     * scrollable range, 0 at the top and exactly 1 at the bottom (0 when it
     * fits), for the header's reading line.
     */
    fun onScrollFraction(fraction: Float) = Unit

    /** How much of the page the viewport shows, 0 to 1, for the fast-scroll thumb (long pages only). */
    fun onVisibleFraction(visible: Float) = Unit

    fun onRendererStateChanged(state: RendererState) = Unit

    /** A non-fatal problem reported by the page, for logs. */
    fun onPageError(message: String) = Unit
}

/** Editing in Preview: text edits, selection state, formatting and tables. */
interface PreviewEditingListener {
    /**
     * Edits typed in Preview, relative to [baseRev]: hand them to the
     * `PreviewEditor` whose `epoch` is [epoch] (`onPageEdits` rebases them).
     * [epoch] is `PageMessage.UNSTAMPED` from a page that did not say.
     */
    fun onSourceEdits(
        baseRev: Int,
        edits: List<TextEdit>,
        epoch: Int = PageMessage.UNSTAMPED,
    ) = Unit

    fun onEditingChanged(editing: Boolean) = Unit

    /** The selection while editing, in the page's text at [baseRev]; see `PreviewEditor.formatState`. */
    fun onSelection(
        from: Int,
        to: Int,
        marks: Set<String>,
        inTable: Boolean,
        baseRev: Int,
    ) = Unit

    /** A strip key or shortcut, with its source range: hand it to `PreviewEditor.onFormatRequest`. */
    fun onFormatRequest(request: PreviewFormatRequest) = Unit

    /** The page lost step with the host: hand it to `PreviewEditor.onResync`, which reloads it. */
    fun onResync(
        reason: String,
        epoch: Int = PageMessage.UNSTAMPED,
    ) = Unit

    /**
     * A change in Preview could not be written back (typing into an emoji or
     * raw HTML) and was undone on screen. Say so, and offer Code at [line].
     */
    fun onEditRefused(line: Int) = Unit

    fun onTableMenu(
        line: Int,
        row: Int,
        column: Int,
    ) = Unit
}

/** Taps and long-presses on content: links, headings, media, and exports. */
interface PreviewContentListener {
    /** Show the link chip (Open · Copy link, plus Edit link when tap-to-edit is on); never open directly (SEC-7). */
    fun onLinkTapped(
        target: LinkTarget,
        line: Int,
    ) = Unit

    /** Long-press on a heading: the section menu. */
    fun onHeadingLongPress(
        line: Int,
        level: Int,
        text: String,
    ) = Unit

    /** The "Edit in Code" chip on a block Preview cannot edit. */
    fun onEditInCode(line: Int) = Unit

    fun onOpenViewer(
        kind: MediaKind,
        index: Int,
        line: Int,
    ) = Unit

    fun onMediaLongPress(
        kind: MediaKind,
        index: Int,
        line: Int,
    ) = Unit

    /**
     * The page asked why some pictures beside the document cannot be shown
     * and was told [statuses] (by the page's key). The host can offer "Allow
     * access" from ⋮ when any is [ImageStatus.NO_ACCESS].
     */
    fun onImageStatuses(statuses: Map<String, ImageStatus>) = Unit

    /** A picture placeholder's "Allow access": ask the user for the document's folder. */
    fun onAllowFolder() = Unit

    /** A diagram export: SVG text as UTF-8, or PNG bytes. */
    fun onExportResult(
        requestId: Int,
        mime: String,
        bytes: ByteArray,
    ) = Unit
}

/** The document as it stands, so the preview can reload itself after a renderer death. */
fun interface DocumentSource {
    fun snapshot(): DocumentSnapshot
}

/**
 * @property text the characters; an immutable snapshot is fine and is never
 *   copied into one String on the main thread (PERF-12).
 */
data class DocumentSnapshot(
    val text: CharSequence,
    val rev: Int,
    /** The binding's epoch, which the reload carries (see `HostMessage.Load.epoch`). */
    val epoch: Int = 0,
)
