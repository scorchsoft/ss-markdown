package com.scorchsoft.ssmarkdown.text

import java.io.OutputStream

/**
 * A [PieceTree] together with what it takes to edit and save it: the
 * file's [TextFileFormat] and an [UndoStack]. Every change goes through
 * [replace] (or undo/redo), which is the single place that keeps the three
 * consistent — in particular, a mixed-line-ending file's per-line record
 * follows the lines as they move.
 *
 * Owned by the editing thread, like the tree. Hand a [snapshot] and
 * [format] to a background save.
 *
 * Known limit: in a mixed-ending file, a break that is deleted and then
 * restored by undo comes back in the dominant style, so undoing back to
 * the opened state is not byte-identical for those lines. Uniform files,
 * which are nearly all files, are unaffected.
 *
 * Because every change passes through here, this is also where an
 * [EditObserver] hears each op: the recovery journal records exactly
 * what changed without depending on every caller to report it.
 */
@Suppress("TooManyFunctions") // Edit, undo, groups and observers: the one place a document changes.
public class TextDocument(
    /** The live text. Read it freely; change it only through this class, or undo and line endings drift. */
    public val text: PieceTree,
    format: TextFileFormat = TextFileFormat.DEFAULT,
    private val history: UndoStack = UndoStack(),
) {
    /** How the text is written back. Changes as edits move lines in a mixed file. */
    public var format: TextFileFormat = format
        private set

    /**
     * Bumped by every change, including undo and redo. Views key their
     * caches on it (docs/robustness.md: cache by line ID + edit version).
     */
    public var version: Long = 0
        private set

    private val observers = ArrayList<EditObserver>(2)

    init {
        // The stack's own decisions reach observers in the order it makes them (ADR 0016).
        history.listener =
            object : UndoListener {
                override fun onStepSealed() = tell { it.onStepSealed() }

                override fun onUndo(opCount: Int) = tell { it.onUndo(opCount) }

                override fun onRedo(opCount: Int) = tell { it.onRedo(opCount) }
            }
    }

    /** True when there is something to undo. */
    public val canUndo: Boolean get() = history.canUndo

    /** True when there is something to redo. */
    public val canRedo: Boolean get() = history.canRedo

    /** Replaces `[offset, offset + length)` with [replacement], records it for undo, and returns the op. */
    public fun replace(
        offset: Int,
        length: Int,
        replacement: CharSequence,
    ): EditOp {
        val op = EditOp(offset, text.slice(offset, offset + length), replacement)
        if (!op.isNoOp) {
            // Grouping is decided first, so an observer hears a sealed step before the op that follows it.
            history.prepare(op)
            apply(op)
            history.record(op)
        }
        return op
    }

    /** Undoes the last step and returns it, so the caller can put the cursor back; null if none. */
    public fun undo(): EditGroup? = history.undo(::apply)

    /** Redoes the last undone step and returns it; null if none. */
    public fun redo(): EditGroup? = history.redo(::apply)

    /** Starts a compound edit that undoes as one step. */
    public fun beginEditGroup(): Unit = history.beginGroup()

    /** Ends the compound edit started by [beginEditGroup]. */
    public fun endEditGroup(): Unit = history.endGroup()

    /** Ends the current typing run, e.g. on a cursor jump. */
    public fun breakTypingRun(): Unit = history.breakTypingRun()

    /** The newest undo and redo steps as data, up to [maxBytes]; see [UndoStack.exportTail]. */
    public fun exportHistory(maxBytes: Long = UndoStack.HISTORY_TAIL_MAX_BYTES): UndoHistory =
        history.exportTail(maxBytes)

    /** True when [exportHistory] with [maxBytes] would keep a step; see [UndoStack.hasTail]. */
    public fun hasHistoryTail(maxBytes: Long = UndoStack.HISTORY_TAIL_MAX_BYTES): Boolean = history.hasTail(maxBytes)

    /**
     * Puts back undo and redo steps a process death took (ADR 0016),
     * replacing the stacks. Observers hear nothing: no text changes.
     *
     * The steps must fit the text as it is now. That is checked where it
     * is cheap: the newest undo step's last op must have left its inserted
     * text at its offset, and the next redo step's first op must find its
     * deleted text there. If either does not, nothing is restored and the
     * answer is false, because undoing a step that does not fit would
     * corrupt the text rather than restore it.
     */
    public fun restoreHistory(steps: UndoHistory): Boolean {
        val newest = steps.undo.lastOrNull()?.lastOrNull()
        val next = steps.redo.lastOrNull()?.firstOrNull()
        val fits =
            (newest == null || holds(newest.offset, newest.inserted)) &&
                (next == null || holds(next.offset, next.deleted))
        if (fits) history.restore(steps)
        return fits
    }

    private fun holds(
        offset: Int,
        chars: CharSequence,
    ): Boolean {
        val end = offset + chars.length
        return end <= text.length && text.getText(offset, end).contentEquals(chars)
    }

    /**
     * Adds [observer], which hears every op from now on, in the order
     * applied; see [EditObserver]. Adding the same observer twice has no effect.
     */
    public fun addEditObserver(observer: EditObserver) {
        if (observer !in observers) observers += observer
    }

    /** Removes [observer]; it hears nothing more. */
    public fun removeEditObserver(observer: EditObserver) {
        observers -= observer
    }

    /** Converts every line break on save to [ending] (the "Line endings" setting). */
    public fun convertLineEndings(ending: LineEnding) {
        format = format.copy(lineEndings = LineEndings.Uniform(ending))
        version++
    }

    /** An immutable view of the text for another thread. */
    public fun snapshot(): Snapshot = text.snapshot()

    /** Writes the file's bytes, in its own format, to [out]. */
    public fun writeTo(out: OutputStream): Unit = Utf8Encoder.write(text.snapshot(), format, out)

    /** Tells every observer; a copy, so an observer may remove itself while it is told. */
    private inline fun tell(event: (EditObserver) -> Unit) {
        if (observers.isNotEmpty()) observers.toList().forEach(event)
    }

    private fun apply(op: EditOp) {
        // A copy, so an observer may remove itself while it is told.
        val told = if (observers.isEmpty()) emptyList() else observers.toList()
        told.forEach { it.beforeEdit(op) }
        val endings = format.lineEndings
        if (endings is LineEndings.Mixed) {
            val firstLine = text.positionAt(op.offset).line
            val moved = endings.afterEdit(firstLine, LineBreaks.count(op.deleted), LineBreaks.count(op.inserted))
            format = format.copy(lineEndings = moved)
        }
        op.applyTo(text)
        version++
        told.forEach { it.afterEdit(op) }
    }

    /** Ways to open a document. */
    public companion object {
        /** A document over a successfully decoded file. */
        public fun from(decoded: DecodeResult.Decoded): TextDocument = TextDocument(decoded.text, decoded.format)
    }
}
