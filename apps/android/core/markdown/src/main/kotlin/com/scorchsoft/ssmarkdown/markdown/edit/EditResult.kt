package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.text.EditOp

/**
 * What an [EditCommand] wants done: the [ops] to apply and where the
 * [selection] lands afterwards.
 *
 * The ops never overlap and are sorted by **descending** offset, each
 * offset in the coordinates of the text the command read. Applying them
 * in list order is therefore valid on the live text: an op never moves the
 * text an op later in the list points at. Undo groups them as one step
 * (`TextDocument.beginEditGroup`).
 *
 * Each op is minimal. Common leading and trailing chars between what was
 * there and what replaces it are trimmed away, so turning `- ` into `1. `
 * replaces one char, and ticking a task box replaces exactly one
 * (docs/research-findings.md R12). Nothing outside an op changes.
 */
public class EditResult internal constructor(
    /** The changes, highest offset first. Empty when the command had nothing to do. */
    public val ops: List<EditOp>,
    /** The selection after the ops, in the new text's coordinates. */
    public val selection: TextSelection,
) {
    /** True when the command changes no text (it may still move the selection). */
    public val isNoOp: Boolean get() = ops.isEmpty()

    /** [text] with the ops applied. O(length): for tests, previews of a small range and simple callers. */
    public fun applyTo(text: CharSequence): String {
        val out = StringBuilder(text)
        for (op in ops) out.replace(op.offset, op.offset + op.deleted.length, op.inserted.toString())
        return out.toString()
    }

    override fun toString(): String = "EditResult(ops=$ops, selection=$selection)"

    /** Results that need no builder. */
    public companion object {
        /** No change to the text; the selection becomes [selection]. */
        public fun unchanged(selection: TextSelection): EditResult = EditResult(emptyList(), selection)
    }
}
