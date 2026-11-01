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
 * and the keyboard's composing region, and every edit the keyboard, a
 * hardware key or the host can make. It has no Android dependency, so the
 * `InputConnection` semantics are tested here on the host JVM, and
 * [CodeInputConnection] stays a thin adapter.
 *
 * Offsets are UTF-16 chars into the document, as the `InputConnection`
 * API counts them. The selection has a direction: the [anchor] stays put
 * and the [caret] (the focus) moves, so Shift+arrows and handle drags
 * extend from the right end.
 *
 * ### Batch edits
 * Keyboards wrap several calls in `beginBatchEdit`/`endBatchEdit`. Batches
 * do not nest in effect: only the outermost end reports the settled
 * selection, through [Listener.onSelectionSettled], so the keyboard hears
 * about the cursor once per gesture, not once per call (robustness.md).
 *
 * Hard keys, caret movement and the keyboard's reads live beside this class
 * (`SessionKeys.kt`, `SessionReads.kt`, `SessionHistory.kt`) so that each
 * file stays one idea. Owned by the main thread, like the document.
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

    /** Selection end (≥ [selectionStart]). */
    var selectionEnd: Int = 0
        private set

    /** True when the caret is at [selectionStart], i.e. the selection was extended backwards. */
    var caretAtStart: Boolean = false
        private set

    /** Start of the keyboard's composing (underlined) region, or -1 when there is none. */
    var composingStart: Int = NONE
        private set

    /** End of the composing region, or -1. */
    var composingEnd: Int = NONE
        private set

    /** Set by the view; null in tests that only check text. */
    var listener: Listener? = null

    /** Grapheme maths for the keys in `SessionKeys.kt`; one per session because it holds a BreakIterator. */
    internal val graphemes = GraphemeBoundaries()

    private var batchDepth = 0
    private var selectionDirty = false

    /** The caret offset: the moving end of the selection. */
    val caret: Int get() = if (caretAtStart) selectionStart else selectionEnd

    /** The fixed end of the selection; equal to [caret] when nothing is selected. */
    val anchor: Int get() = if (caretAtStart) selectionEnd else selectionStart

    /** True when some text is selected. */
    val hasSelection: Boolean get() = selectionStart != selectionEnd

    /** True when a composing region is active. */
    val isComposing: Boolean get() = composingStart != NONE

    /** True while a keyboard batch is open. */
    val inBatch: Boolean get() = batchDepth > 0

    /** Swaps in a new document with the caret at its start; the view then restarts input. */
    fun reset(newDocument: TextDocument) {
        document = newDocument
        batchDepth = 0
        selectionStart = 0
        selectionEnd = 0
        caretAtStart = false
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
        val inserted = replaceRange(start, end, text)
        clearComposing()
        placeCursor(start, start + inserted, newCursorPosition)
    }

    /** Replaces the composing region (or the selection) with [text] and marks it as composing. */
    fun setComposingText(
        text: CharSequence,
        newCursorPosition: Int,
    ) {
        val (start, end) = replacementRange()
        val inserted = replaceRange(start, end, text)
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
        if (afterEnd > selectionEnd) replaceRange(selectionEnd, afterEnd, "")
        var beforeStart = clamp(selectionStart - before.coerceAtLeast(0))
        if (beforeStart in 1 until selectionStart && text[beforeStart].isLowSurrogate()) beforeStart--
        if (beforeStart < selectionStart) replaceRange(beforeStart, selectionStart, "")
        notifySelection()
    }

    /**
     * Moves the selection. As in `InputConnection.setSelection`, [end] is
     * where the caret goes, so `start > end` is a backwards selection.
     */
    fun setSelection(
        start: Int,
        end: Int,
    ) {
        val a = clamp(start)
        val b = clamp(end)
        selectionStart = minOf(a, b)
        selectionEnd = maxOf(a, b)
        caretAtStart = b < a
        notifySelection()
    }

    /** Places a caret, ending any composition first (a tap elsewhere commits the word). */
    fun moveCaretTo(offset: Int) {
        clearComposing()
        document.breakTypingRun()
        setSelection(offset, offset)
    }

    /** Keeps the [anchor] and moves the caret to [focus]: Shift+arrows and handle drags. */
    fun extendSelection(focus: Int) {
        clearComposing()
        document.breakTypingRun()
        setSelection(anchor, focus)
    }

    /** Selects the whole document. */
    fun selectAll() {
        clearComposing()
        setSelection(0, document.text.length)
    }

    /**
     * An edit from outside the keyboard (a toolbar command, a Preview
     * round-trip, paste): commits any composition first so the keyboard's
     * word is never lost or doubled (robustness.md §5a.4), replaces
     * `[start, end)` with [text] and selects `[selectStart, selectEnd)`
     * afterwards (defaults: a caret after the inserted text).
     */
    fun applyEdit(
        start: Int,
        end: Int,
        text: CharSequence,
        selectStart: Int = NONE,
        selectEnd: Int = NONE,
    ) {
        finishComposingText()
        val from = clamp(minOf(start, end))
        val inserted = replaceRange(from, clamp(maxOf(start, end)), text)
        val caretAfter = from + inserted
        val newStart = if (selectStart == NONE) caretAfter else selectStart
        val newEnd = if (selectEnd == NONE) newStart else selectEnd
        setSelection(newStart, newEnd)
    }

    // --------------------------------------------------------------- core

    /**
     * Replaces `[start, end)` with [rawText] in the document and moves the
     * selection and composing region the way the text moved. The single
     * place that edits, so the line-change report is always right. Returns
     * the length actually inserted: CRLF and lone CR arrive as one `'\n'`,
     * as the document stores them, so offsets after it are computed on
     * that, not on what the keyboard sent.
     */
    internal fun replaceRange(
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

    /** Tells the listener about lines changed behind the session's back (undo, redo), then the selection. */
    internal fun reportExternalChange(
        change: LineChange,
        caretAfter: Int,
    ) {
        clearComposing()
        listener?.onTextChanged(change)
        setSelection(caretAfter, caretAfter)
    }

    internal fun clearComposing() {
        composingStart = NONE
        composingEnd = NONE
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
        setSelection(target, target)
    }

    private fun setComposing(
        start: Int,
        end: Int,
    ) {
        composingStart = start
        composingEnd = end
    }

    internal fun notifySelection() {
        if (batchDepth > 0) {
            selectionDirty = true
        } else {
            selectionDirty = false
            listener?.onSelectionSettled()
        }
    }

    internal fun clamp(offset: Int): Int = offset.coerceIn(0, document.text.length)

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
