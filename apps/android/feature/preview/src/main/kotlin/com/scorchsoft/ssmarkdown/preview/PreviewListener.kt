package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.Crumb
import com.scorchsoft.ssmarkdown.preview.bridge.LinkTarget
import com.scorchsoft.ssmarkdown.preview.bridge.MediaKind
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

    fun onRendererStateChanged(state: RendererState) = Unit

    /** A non-fatal problem reported by the page, for logs. */
    fun onPageError(message: String) = Unit
}

/** Editing in Preview: text edits, selection state, formatting and tables. */
interface PreviewEditingListener {
    /** Edits typed in Preview, relative to [baseRev]; apply from last to first. */
    fun onSourceEdits(
        baseRev: Int,
        edits: List<TextEdit>,
    ) = Unit

    fun onEditingChanged(editing: Boolean) = Unit

    fun onSelection(
        from: Int,
        to: Int,
        marks: Set<String>,
        inTable: Boolean,
    ) = Unit

    fun onFormatRequest(
        action: String,
        from: Int,
        to: Int,
    ) = Unit

    fun onTableMenu(
        line: Int,
        row: Int,
        column: Int,
    ) = Unit
}

/** Taps and long-presses on content: links, headings, media, and exports. */
interface PreviewContentListener {
    /** Show the Open · Edit link chip, or open directly when tap-to-edit is off. */
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

data class DocumentSnapshot(
    val text: String,
    val rev: Int,
)
