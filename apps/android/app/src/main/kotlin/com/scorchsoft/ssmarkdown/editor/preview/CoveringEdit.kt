package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import com.scorchsoft.ssmarkdown.text.EditOp

/**
 * One [TextEdit] that covers a run of edits applied one after another:
 * an undo group, a replace-all, a merge. The preview's edit protocol
 * wants edits relative to one "before" text, sorted and non-overlapping;
 * sequential ops are neither, so this finds the span they touched
 * between them and sends that span's new text.
 *
 * Only lengths are needed to find the span: the untouched prefix is the
 * lowest offset any op touched, and the untouched suffix is the fewest
 * characters any op left after its deletion. So it never reads the
 * "before" text, and costs O(ops) plus the span's new text.
 */
object CoveringEdit {
    /** The edit turning the text before [ops] (of [beforeLength] chars) into [after]; null if nothing changed. */
    fun of(
        beforeLength: Int,
        ops: List<EditOp>,
        after: CharSequence,
    ): TextEdit? {
        val changing = ops.filterNot(EditOp::isNoOp)
        if (changing.isEmpty()) return null
        var length = beforeLength
        var prefix = Int.MAX_VALUE
        var suffix = Int.MAX_VALUE
        for (op in changing) {
            prefix = minOf(prefix, op.offset)
            suffix = minOf(suffix, length - op.offset - op.deleted.length)
            length += op.inserted.length - op.deleted.length
        }
        check(length == after.length) { "ops do not lead from $beforeLength chars to ${after.length}" }
        val from = prefix.coerceIn(0, beforeLength)
        val to = (beforeLength - suffix).coerceIn(from, beforeLength)
        val end = (length - suffix).coerceIn(from, length)
        return TextEdit(from, to, after.subSequence(from, end).toString())
    }
}
