package com.scorchsoft.ssmarkdown.editor

// Hard keys and caret movement on an EditingSession. Extensions rather
// than members so the session itself stays the InputConnection contract;
// they reach only its internal edit and selection primitives.

/** Enter: a line break in place of the selection. */
fun EditingSession.pressEnter(): Unit = typeKey("\n")

/**
 * A char from a key (Enter, Tab, a hardware letter): typed at the selection, keeping the keyboard's
 * composing word. `commitText` would type over that word, and a keyboard that sends Enter as a key
 * while a word is underlined lost the word (edge-cases "Enter as a key while composing").
 */
fun EditingSession.typeKey(text: CharSequence) {
    clearComposing()
    commitText(text, 1)
}

/** Backspace: deletes the selection, or the grapheme before the caret. */
fun EditingSession.pressBackspace() {
    clearComposing()
    if (hasSelection) {
        commitText("", 1)
    } else if (caret > 0) {
        val start = graphemes.previous(document.text, caret)
        replaceRange(start, caret, "")
        notifySelection()
    }
}

/** Forward delete: deletes the selection, or the grapheme after the caret. */
fun EditingSession.pressForwardDelete() {
    clearComposing()
    if (hasSelection) {
        commitText("", 1)
    } else if (caret < document.text.length) {
        replaceRange(caret, graphemes.next(document.text, caret), "")
        notifySelection()
    }
}

/**
 * Left/right arrow: one grapheme. Without [extend] a selection collapses
 * to the side the arrow points at, as on every desktop; with it (Shift)
 * the caret moves and the anchor stays.
 */
fun EditingSession.moveCaretByGrapheme(
    forward: Boolean,
    extend: Boolean = false,
) {
    val text = document.text
    when {
        extend -> extendSelection(if (forward) graphemes.next(text, caret) else graphemes.previous(text, caret))
        hasSelection -> moveCaretTo(if (forward) selectionEnd else selectionStart)
        else -> moveCaretTo(if (forward) graphemes.next(text, caret) else graphemes.previous(text, caret))
    }
}

/** Ctrl+Left/Right: to the previous word start or past the next word, optionally extending. */
fun EditingSession.moveCaretByWord(
    forward: Boolean,
    words: WordFinder,
    extend: Boolean = false,
) {
    val text = document.text
    val target = if (forward) words.nextWordEnd(text, caret) else words.previousWordStart(text, caret)
    moveOrExtend(target, extend)
}

/** Up/down arrow on whole lines: the same column on the neighbouring line, clamped to its length. */
fun EditingSession.moveCaretByLine(
    delta: Int,
    extend: Boolean = false,
) {
    val text = document.text
    val position = text.positionAt(caret)
    val line = (position.line + delta).coerceIn(0, text.lineCount - 1)
    moveOrExtend(text.offsetAt(line, minOf(position.column, text.lineLength(line))), extend)
}

/** Home/End: the start or end of the caret's line; with Ctrl, of the document. */
fun EditingSession.moveCaretToBoundary(
    end: Boolean,
    wholeDocument: Boolean,
    extend: Boolean = false,
) {
    val text = document.text
    val line = text.positionAt(caret).line
    val target =
        when {
            wholeDocument && end -> text.length
            wholeDocument -> 0
            end -> text.lineEndOffset(line)
            else -> text.lineStartOffset(line)
        }
    moveOrExtend(target, extend)
}

/** Moves the caret to [offset], or extends the selection to it when [extend]. */
fun EditingSession.moveOrExtend(
    offset: Int,
    extend: Boolean,
) {
    if (extend) extendSelection(offset) else moveCaretTo(offset)
}
