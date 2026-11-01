package com.scorchsoft.ssmarkdown.editor

// What the keyboard and accessibility read from an EditingSession. Every
// read is capped: handing a keyboard a whole 100 MB document is the
// select-all ANR robustness.md exists to prevent.

/** Up to [n] chars before the selection, capped at [EditingSession.MAX_IME_WINDOW]. */
fun EditingSession.textBeforeCursor(n: Int): CharSequence {
    val count = n.coerceIn(0, EditingSession.MAX_IME_WINDOW)
    return document.text.getText((selectionStart - count).coerceAtLeast(0), selectionStart)
}

/** Up to [n] chars after the selection, capped at [EditingSession.MAX_IME_WINDOW]. */
fun EditingSession.textAfterCursor(n: Int): CharSequence {
    val count = n.coerceIn(0, EditingSession.MAX_IME_WINDOW)
    return document.text.getText(selectionEnd, clamp(selectionEnd + count))
}

/** The selected text, or null when the selection is empty; never more than [EditingSession.MAX_IME_WINDOW] chars. */
fun EditingSession.selectedText(): CharSequence? {
    if (!hasSelection) return null
    return document.text.getText(selectionStart, minOf(selectionEnd, selectionStart + EditingSession.MAX_IME_WINDOW))
}

/**
 * A window of at most [maxChars] (capped at [EditingSession.MAX_IME_WINDOW])
 * centred on the selection: what `getExtractedText` and the surrounding-text
 * APIs hand the keyboard.
 */
fun EditingSession.textWindow(maxChars: Int = EditingSession.MAX_IME_WINDOW): TextWindow =
    windowAround(maxChars.coerceIn(0, EditingSession.MAX_IME_WINDOW))

/**
 * A window of at most [budget] chars centred on the selection, with no IME
 * cap: accessibility uses it with its own 100K limit.
 */
internal fun EditingSession.windowAround(budget: Int): TextWindow {
    val length = document.text.length
    val centre = (selectionStart + selectionEnd) / 2
    val start = (centre - budget / 2).coerceIn(0, (length - budget).coerceAtLeast(0))
    val end = minOf(length, start + budget)
    return TextWindow(start, document.text.getText(start, end))
}
