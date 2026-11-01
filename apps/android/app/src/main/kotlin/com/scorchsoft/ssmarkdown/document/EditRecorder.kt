package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.Snapshot

/**
 * Turns the editor's line-level change reports into journal edits.
 *
 * `MarkdownCodeView` edits its `TextDocument` directly and tells the host
 * only which lines changed (`LineChange`). The journal needs the text
 * itself, so this keeps the snapshot from before the edit (an O(pieces)
 * copy, never a copy of the chars) and rebuilds one [EditOp] covering the
 * changed lines: their old text from the snapshot, their new text from
 * the live tree. Replaying it reproduces the edit exactly; it is merely
 * line-granular rather than char-granular.
 */
class EditRecorder(
    initial: Snapshot,
) {
    private var before: Snapshot = initial

    /** The text as it was before the next edit to be recorded. */
    val snapshot: Snapshot get() = before

    /** Forgets the old snapshot: the document was replaced or reloaded. */
    fun reset(snapshot: Snapshot) {
        before = snapshot
    }

    /**
     * The edit that turned the remembered text into [after]: old lines
     * `[firstLine, firstLine + removedBreaks]` became new lines
     * `[firstLine, firstLine + addedBreaks]`.
     */
    fun record(
        after: PieceTree,
        firstLine: Int,
        removedBreaks: Int,
        addedBreaks: Int,
    ): EditOp {
        val old = before
        val oldStart = old.lineStartOffset(firstLine.coerceIn(0, old.lineCount - 1))
        val oldEnd = old.lineEndOffset((firstLine + removedBreaks).coerceIn(0, old.lineCount - 1))
        val newStart = after.lineStartOffset(firstLine.coerceIn(0, after.lineCount - 1))
        val newEnd = after.lineEndOffset((firstLine + addedBreaks).coerceIn(0, after.lineCount - 1))
        before = after.snapshot()
        return EditOp(oldStart, old.subSequence(oldStart, oldEnd).toString(), after.getText(newStart, newEnd))
    }
}
