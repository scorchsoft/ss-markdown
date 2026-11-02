package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.PieceTree

// Undo and redo through an EditingSession. TextDocument applies the ops
// straight to the tree, so the session's caches never see them one by one;
// these functions turn the returned group into the one LineChange that
// covers it, so the layout cache, the height map and the tokenizer stay
// exact without being cleared.

/** Undoes the last step (Ctrl+Z). Returns false when there was nothing to undo. */
fun EditingSession.undo(): Boolean {
    finishComposingText()
    val group = document.undo() ?: return false
    val applied = group.ops.asReversed().map { it.inverse() }
    report(applied)
    return true
}

/** Redoes the last undone step (Ctrl+Shift+Z, Ctrl+Y). Returns false when there was nothing to redo. */
fun EditingSession.redo(): Boolean {
    finishComposingText()
    val group = document.redo() ?: return false
    report(group.ops)
    return true
}

private fun EditingSession.report(applied: List<EditOp>) {
    if (applied.isEmpty()) return
    val change = EditEnvelope.of(applied).lineChange(document.text)
    val last = applied.last()
    reportExternalChange(change, last.offset + last.inserted.length)
}

/**
 * The smallest range, in the text *after* a run of ops, outside which
 * nothing changed: [start] until [end], with [breakDelta] more line breaks
 * than before. Everything before [start] kept its offset and everything
 * after [end] only shifted, so the lines outside it keep their caches.
 */
internal class EditEnvelope(
    val start: Int,
    val end: Int,
    val breakDelta: Int,
) {
    /** The change in [LineChange] terms, read from the tree the ops were applied to. */
    fun lineChange(after: PieceTree): LineChange {
        val firstLine = after.positionAt(start).line
        val added = after.positionAt(end).line - firstLine
        return LineChange(firstLine, added - breakDelta, added)
    }

    /** Builds envelopes. */
    companion object {
        /** The envelope of [ops], applied in list order. */
        fun of(ops: List<EditOp>): EditEnvelope {
            var start = Int.MAX_VALUE
            var end = Int.MIN_VALUE
            var breaks = 0
            for (op in ops) {
                val removedEnd = op.offset + op.deleted.length
                val delta = op.inserted.length - op.deleted.length
                if (start <= end) {
                    start = moved(start, op.offset, removedEnd, delta, op.offset)
                    end = moved(end, op.offset, removedEnd, delta, op.offset + op.inserted.length)
                }
                start = minOf(start, op.offset)
                end = maxOf(end, op.offset + op.inserted.length)
                breaks += op.inserted.count { it == '\n' } - op.deleted.count { it == '\n' }
            }
            return EditEnvelope(start, end, breaks)
        }

        /** Where [offset] lands after `[from, to)` grew by [delta]; inside the removed range it goes to [inside]. */
        private fun moved(
            offset: Int,
            from: Int,
            to: Int,
            delta: Int,
            inside: Int,
        ): Int =
            when {
                offset <= from -> offset
                offset >= to -> offset + delta
                else -> inside
            }
    }
}
