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
 */
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

    /** Converts every line break on save to [ending] (the "Line endings" setting). */
    public fun convertLineEndings(ending: LineEnding) {
        format = format.copy(lineEndings = LineEndings.Uniform(ending))
        version++
    }

    /** An immutable view of the text for another thread. */
    public fun snapshot(): Snapshot = text.snapshot()

    /** Writes the file's bytes, in its own format, to [out]. */
    public fun writeTo(out: OutputStream): Unit = Utf8Encoder.write(text.snapshot(), format, out)

    private fun apply(op: EditOp) {
        val endings = format.lineEndings
        if (endings is LineEndings.Mixed) {
            val firstLine = text.positionAt(op.offset).line
            val moved = endings.afterEdit(firstLine, LineBreaks.count(op.deleted), LineBreaks.count(op.inserted))
            format = format.copy(lineEndings = moved)
        }
        op.applyTo(text)
        version++
    }

    /** Ways to open a document. */
    public companion object {
        /** A document over a successfully decoded file. */
        public fun from(decoded: DecodeResult.Decoded): TextDocument = TextDocument(decoded.text, decoded.format)
    }
}
