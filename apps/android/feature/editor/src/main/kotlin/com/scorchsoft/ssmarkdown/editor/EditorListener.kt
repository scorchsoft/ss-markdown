package com.scorchsoft.ssmarkdown.editor

/**
 * What a host (the screen's view model, the Compose wrapper) hears from a
 * [MarkdownCodeView]. Every method has an empty default, so a host
 * overrides only what it needs. All calls arrive on the main thread.
 */
interface EditorListener {
    /** The selection settled at `[start, end)` (equal for a caret), after the keyboard's batch closed. */
    fun onSelectionChanged(
        start: Int,
        end: Int,
    ) = Unit

    /**
     * The text changed: [change] says which lines, [version] is the
     * document's new edit version. The host marks the file dirty and feeds
     * the journal from here.
     */
    fun onTextEdited(
        change: LineChange,
        version: Long,
    ) = Unit

    /** The source line at the top of the pane changed (ux-spec "Position continuity": the anchor). */
    fun onAnchorLineChanged(line: Int) = Unit

    /**
     * The zoom changed to [percent]. [pinching] is true while fingers are
     * down (show "Pinching · 112%"), and false once for the settled value,
     * which the host stores per file.
     */
    fun onZoomChanged(
        percent: Int,
        pinching: Boolean,
    ) = Unit

    /**
     * A copy or cut of [chars] chars was refused because it would not fit
     * through the clipboard's Binder limit (robustness.md §5). The host
     * offers "Share as file" instead.
     */
    fun onCopyTooLarge(chars: Int) = Unit

    /**
     * A hardware-keyboard shortcut the editor does not own (Ctrl+S, Ctrl+F,
     * Ctrl+B, Tab...). Return true if the host handled it.
     */
    fun onShortcut(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
    ): Boolean = false

    /**
     * A large paste ([MarkdownCodeView.pasteText]) moved on: show progress
     * while [PasteProgress.done] is false. The paste is one undo step, and
     * typing during it is applied after it.
     */
    fun onPasteProgress(progress: PasteProgress) = Unit

    /**
     * May a large paste of [chars] go in? Asked before it starts when the
     * clip says its length, and again once it has become text (the only
     * time a clip of unknown length has one). False refuses it, leaving
     * the document as it was; the host says why (docs/robustness.md Part
     * 2a, "Admission control"; performance review PERF-G).
     */
    fun admitPaste(chars: Int): Boolean = true
}
