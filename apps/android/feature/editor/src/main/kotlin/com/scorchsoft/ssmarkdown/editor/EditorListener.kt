package com.scorchsoft.ssmarkdown.editor

/**
 * What a host (the screen's view model, the Compose wrapper) hears from a
 * [MarkdownCodeView]. Every method has an empty default, so a host
 * overrides only what it needs. All calls arrive on the main thread.
 */
@Suppress("TooManyFunctions") // One no-op default per event the view reports; nothing to split.
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
     * How far through the document the pane is scrolled, for the reading
     * line: the scroll offset over the scrollable range, from the height
     * map, so wrapped lines count at their height and nothing is measured
     * to answer. 0 at the top and when the document fits, exactly 1 at the
     * very bottom.
     */
    fun onScrollFractionChanged(fraction: Float) = Unit

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

    /**
     * A key whose effect depends on the Markdown around the caret ([EditKey]),
     * with no word being composed. Return true when the host made the edit
     * itself; false lets the view type a line break, delete or type as usual.
     */
    fun onEditKey(key: EditKey): Boolean = false

    /**
     * The selection menu's "Copy formatted text" over `[start, end)`: the
     * host puts the selection's rendering on the clipboard. False when it
     * cannot (the menu then never offers it: [canCopyFormatted]).
     */
    fun onCopyFormatted(
        start: Int,
        end: Int,
    ): Boolean = false

    /** Whether the host can answer [onCopyFormatted]. */
    val canCopyFormatted: Boolean get() = false
}

/** The keys offered to [EditorListener.onEditKey]. */
sealed interface EditKey {
    /**
     * Enter, from a key or a keyboard's committed line break: list and quote
     * continuation (docs/ux-spec.md "Enter in a list").
     */
    data object Enter : EditKey

    /**
     * Backspace, from a key or a keyboard's one-char delete: at the start of
     * a list item's text the marker comes off (docs/ux-spec.md "Enter in a list").
     */
    data object Backspace : EditKey

    /** A `}`, `]` or `)` typed: first on a code line it takes one indent unit off. */
    data class Closer(
        val text: String,
    ) : EditKey
}
