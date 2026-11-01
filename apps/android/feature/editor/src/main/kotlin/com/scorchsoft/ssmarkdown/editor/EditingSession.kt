package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.text.GraphemeBoundaries
import com.scorchsoft.ssmarkdown.text.TextDocument

/**
 * What an edit did to the lines, in the terms the view's caches need:
 * the line it started on and how many line breaks it removed and added.
 */
data class LineChange(
    val firstLine: Int,
    val removedBreaks: Int,
    val addedBreaks: Int,
)

/**
 * The editing state behind the code view: a [TextDocument] plus selection
 * and the keyboard's composing region, and every edit the keyboard or a
 * hardware key can make. It has no Android dependency, so the
 * `InputConnection` semantics are tested here on the host JVM, and
 * [CodeInputConnection] stays a thin adapter.
 *
 * Offsets are UTF-16 chars into the document, as the `InputConnection`
 * API counts them.
 *
 * ### Batch edits
 * Keyboards wrap several calls in `beginBatchEdit`/`endBatchEdit`. Batches
 * do not nest in effect: only the outermost end reports the settled
 * selection, through [Listener.onSelectionSettled], so the keyboard hears
 * about the cursor once per gesture, not once per call (robustness.md).
 *
 * Owned by the main thread, like the document.
 */
@Suppress("TooManyFunctions") // One small method per InputConnection verb; splitting them would scatter one contract.
class EditingSession(
    document: TextDocument,
) {
    /** Receives every change. The code view is the one listener in practice. */
    interface Listener {
        /** The text changed; called at once, inside any batch, so caches never see stale lines. */
        fun onTextChanged(change: LineChange)

        /** Selection or composing region changed and no batch is open: tell the keyboard. */
        fun onSelectionSettled()
    }

    /** The document being edited. Replaced wholesale by [reset]. */
    var document: TextDocument = document
        private set

    /** Selection start (≤ [selectionEnd]); equal to it when there is just a caret. */
    var selectionStart: Int = 0
        private set

    /** Selection end, which is where the caret is drawn. */
    var selectionEnd: Int = 0
        private set

    /** Start of the keyboard's composing (underlined) region, or -1 when there is none. */
    var composingStart: Int = NONE
        private set

    /** End of the composing region, or -1. */
    var composingEnd: Int = NONE
        private set

    /** Set by the view; null in tests that only check text. */
    var listener: Listener? = null

    private val graphemes = GraphemeBoundaries()
    private var batchDepth = 0
    private var selectionDirty = false

    /** The caret offset (the selection's end). */
    val caret: Int get() = selectionEnd

    /** True when a composing region is active. */
    val isComposing: Boolean get() = composingStart != NONE

    /** Swaps in a new document with the caret at its start; the view then restarts input. */
    fun reset(newDocument: TextDocument) {
        document = newDocument
        selectionStart = 0
        selectionEnd = 0
        clearComposing()
        notifySelection()
    }

    // ---------------------------------------------------------------- batches

    /** Opens a batch; nested calls only count. */
    fun beginBatchEdit() {
        batchDepth++
    }

    /** Closes a batch; the outermost close reports the selection once. Returns true while a batch is still open. */
    fun endBatchEdit(): Boolean {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0 && selectionDirty) notifySelection()
        return batchDepth > 0
    }

    // ------------------------------------------------------- keyboard edits

    /**
     * Replaces the composing region (or else the selection) with [text] and
     * ends composition. [newCursorPosition] follows the `InputConnection`
     * rule: > 0 is relative to the end of the inserted text minus one, ≤ 0
     * relative to its start.
     */
    fun commitText(
        text: CharSequence,
        newCursorPosition: Int,
    ) {
        val (start, end) = replacementRange()
        val inserted = replace(start, end, text)
        clearComposing()
        placeCursor(start, start + inserted, newCursorPosition)
    }

    /** Replaces the composing region (or the selection) with [text] and marks it as composing. */
    fun setComposingText(
        text: CharSequence,
        newCursorPosition: Int,
    ) {
        val (start, end) = replacementRange()
        val inserted = replace(start, end, text)
        if (inserted == 0) clearComposing() else setComposing(start, start + inserted)
        placeCursor(start, start + inserted, newCursorPosition)
    }

    /** Marks existing text `[start, end)` as composing (either order); an empty range clears it. */
    fun setComposingRegion(
        start: Int,
        end: Int,
    ) {
        val a = clamp(minOf(start, end))
        val b = clamp(maxOf(start, end))
        if (a == b) clearComposing() else setComposing(a, b)
        notifySelection()
    }

    /** Keeps the composed text and drops the region. */
    fun finishComposingText() {
        if (!isComposing) return
        clearComposing()
        notifySelection()
    }

    /**
     * Deletes [before] chars before the selection and [after] chars after it,
     * leaving the selection itself alone. Counts are clamped to the document
     * and widened by one rather than split a surrogate pair.
     */
    fun deleteSurroundingText(
        before: Int,
        after: Int,
    ) {
        val text = document.text
        var afterEnd = clamp(selectionEnd + after.coerceAtLeast(0))
        if (afterEnd < text.length && afterEnd > 0 && text[afterEnd].isLowSurrogate()) afterEnd++
        if (afterEnd > selectionEnd) replace(selectionEnd, afterEnd, "")
        var beforeStart = clamp(selectionStart - before.coerceAtLeast(0))
        if (beforeStart in 1 until selectionStart && text[beforeStart].isLowSurrogate()) beforeStart--
        if (beforeStart < selectionStart) replace(beforeStart, selectionStart, "")
        notifySelection()
    }

    /** Moves the selection; the keyboard calls this, and so does a tap. */
    fun setSelection(
        start: Int,
        end: Int,
    ) {
        selectionStart = clamp(minOf(start, end))
        selectionEnd = clamp(maxOf(start, end))
        notifySelection()
    }

    /** Places a caret, ending any composition first (a tap elsewhere commits the word). */
    fun moveCaretTo(offset: Int) {
        clearComposing()
        document.breakTypingRun()
        setSelection(offset, offset)
    }

    // ------------------------------------------------------------ hard keys

    /** Enter: a line break in place of the selection. */
    fun pressEnter(): Unit = commitText("\n", 1)

    /** Backspace: deletes the selection, or the grapheme before the caret. */
    fun pressBackspace() {
        clearComposing()
        if (selectionStart != selectionEnd) {
            commitText("", 1)
        } else if (caret > 0) {
            val start = graphemes.previous(document.text, caret)
            replace(start, caret, "")
            notifySelection()
        }
    }

    /** Forward delete: deletes the selection, or the grapheme after the caret. */
    fun pressForwardDelete() {
        clearComposing()
        if (selectionStart != selectionEnd) {
            commitText("", 1)
        } else if (caret < document.text.length) {
            replace(caret, graphemes.next(document.text, caret), "")
            notifySelection()
        }
    }

    /** Left/right arrow: one grapheme, collapsing any selection. */
    fun moveCaretByGrapheme(forward: Boolean) {
        val text = document.text
        val target = if (forward) graphemes.next(text, caret) else graphemes.previous(text, caret)
        moveCaretTo(target)
    }

    /** Up/down arrow: the same column on the neighbouring line, clamped to its length. */
    fun moveCaretByLine(delta: Int) {
        val text = document.text
        val position = text.positionAt(caret)
        val line = (position.line + delta).coerceIn(0, text.lineCount - 1)
        moveCaretTo(text.offsetAt(line, position.column))
    }

    // ------------------------------------------------------ keyboard reads

    /** Up to [n] chars before the selection, capped at [MAX_IME_WINDOW]. */
    fun textBeforeCursor(n: Int): CharSequence {
        val count = n.coerceIn(0, MAX_IME_WINDOW)
        return document.text.getText((selectionStart - count).coerceAtLeast(0), selectionStart)
    }

    /** Up to [n] chars after the selection, capped at [MAX_IME_WINDOW]. */
    fun textAfterCursor(n: Int): CharSequence {
        val count = n.coerceIn(0, MAX_IME_WINDOW)
        return document.text.getText(selectionEnd, clamp(selectionEnd + count))
    }

    /** The selected text, or null when the selection is empty; never more than [MAX_IME_WINDOW] chars. */
    fun selectedText(): CharSequence? {
        if (selectionStart == selectionEnd) return null
        return document.text.getText(selectionStart, minOf(selectionEnd, selectionStart + MAX_IME_WINDOW))
    }

    /**
     * A window of at most [maxChars] (capped at [MAX_IME_WINDOW]) centred on
     * the selection: what `getExtractedText` and the surrounding-text APIs
     * hand the keyboard. Handing over more makes select-all an ANR.
     */
    fun textWindow(maxChars: Int = MAX_IME_WINDOW): TextWindow {
        val budget = maxChars.coerceIn(0, MAX_IME_WINDOW)
        val length = document.text.length
        val centre = (selectionStart + selectionEnd) / 2
        val start = (centre - budget / 2).coerceIn(0, (length - budget).coerceAtLeast(0))
        val end = minOf(length, start + budget)
        return TextWindow(start, document.text.getText(start, end))
    }

    // --------------------------------------------------------------- core

    /**
     * Replaces `[start, end)` with [text] in the document and moves the
     * selection and composing region the way the text moved. The single
     * place that edits, so the line-change report is always right. Returns
     * the length actually inserted: CRLF and lone CR arrive as one `'\n'`,
     * as the document stores them, so offsets after it are computed on
     * that, not on what the keyboard sent.
     */
    private fun replace(
        start: Int,
        end: Int,
        rawText: CharSequence,
    ): Int {
        val text = normalizeBreaks(rawText)
        val tree = document.text
        val firstLine = tree.positionAt(start).line
        val removed = tree.positionAt(end).line - firstLine
        document.replace(start, end - start, text)
        val added = text.count { it == '\n' }
        val delta = text.length - (end - start)
        selectionStart = shift(selectionStart, start, end, delta)
        selectionEnd = shift(selectionEnd, start, end, delta)
        if (isComposing) {
            composingStart = shift(composingStart, start, end, delta)
            composingEnd = shift(composingEnd, start, end, delta)
            if (composingStart >= composingEnd) clearComposing()
        }
        listener?.onTextChanged(LineChange(firstLine, removed, added))
        return text.length
    }

    private fun normalizeBreaks(text: CharSequence): CharSequence =
        if (text.indexOf('\r') < 0) text else text.toString().replace("\r\n", "\n").replace('\r', '\n')

    /** Where [offset] ends up after `[start, end)` became [delta] chars longer. */
    private fun shift(
        offset: Int,
        start: Int,
        end: Int,
        delta: Int,
    ): Int =
        when {
            offset <= start -> offset
            offset >= end -> offset + delta
            else -> start
        }

    private fun replacementRange(): Pair<Int, Int> =
        if (isComposing) composingStart to composingEnd else selectionStart to selectionEnd

    private fun placeCursor(
        insertedStart: Int,
        insertedEnd: Int,
        newCursorPosition: Int,
    ) {
        val target =
            if (newCursorPosition >
                0
            ) {
                insertedEnd + newCursorPosition - 1
            } else {
                insertedStart + newCursorPosition
            }
        val offset = clamp(target)
        selectionStart = offset
        selectionEnd = offset
        notifySelection()
    }

    private fun setComposing(
        start: Int,
        end: Int,
    ) {
        composingStart = start
        composingEnd = end
    }

    private fun clearComposing() {
        composingStart = NONE
        composingEnd = NONE
    }

    private fun notifySelection() {
        if (batchDepth > 0) {
            selectionDirty = true
        } else {
            selectionDirty = false
            listener?.onSelectionSettled()
        }
    }

    private fun clamp(offset: Int): Int = offset.coerceIn(0, document.text.length)

    /** Limits shared with the keyboard adapter. */
    companion object {
        /** "No composing region", as the `InputConnection` API spells it. */
        const val NONE: Int = -1

        /** The most text ever handed to a keyboard in one call (robustness.md: 32K). */
        const val MAX_IME_WINDOW: Int = 32 * 1024
    }
}

/** A slice of the document handed to the keyboard: its [start] offset and its [text]. */
data class TextWindow(
    val start: Int,
    val text: String,
)
