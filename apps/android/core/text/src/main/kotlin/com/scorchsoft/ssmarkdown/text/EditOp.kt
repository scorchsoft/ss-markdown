package com.scorchsoft.ssmarkdown.text

/**
 * One change to the text: at [offset], [deleted] was replaced by
 * [inserted]. Undo, redo and the journal record these piece-table
 * operations, never copies of the document (docs/robustness.md).
 *
 * [deleted] is normally a zero-copy [Snapshot] slice taken just before the
 * delete, so recording "select all, delete" on a 100 MB file costs
 * O(pieces), and undoing it links those pieces straight back in.
 *
 * Both texts are in memory form: `'\n'` is the only line break. [inserted]
 * is normalised on construction so an op can never disagree with the tree
 * about its own length.
 */
public class EditOp(
    /** Where the change starts, in chars. */
    public val offset: Int,
    /** The text that was there before; empty for a pure insert. */
    public val deleted: CharSequence,
    inserted: CharSequence,
) {
    /** The text that is there after; empty for a pure delete. */
    public val inserted: CharSequence = LineBreaks.normalize(inserted)

    init {
        require(offset >= 0) { "offset $offset is negative" }
    }

    /** True when the op changes nothing, so recording it would only add an empty undo step. */
    public val isNoOp: Boolean get() = deleted.isEmpty() && inserted.isEmpty()

    /** The op that undoes this one. */
    public fun inverse(): EditOp = EditOp(offset, inserted, deleted)

    /** Applies this op to [text], which must hold [deleted] at [offset]. */
    public fun applyTo(text: PieceTree) {
        if (deleted.isNotEmpty()) text.delete(offset, deleted.length)
        if (inserted.isNotEmpty()) text.insert(offset, inserted)
    }

    /** What keeping this op costs, as UTF-16 bytes plus object overhead; [UndoStack] budgets by it. */
    internal val retainedBytes: Long get() = 2L * (deleted.length + inserted.length) + OVERHEAD_BYTES

    override fun toString(): String =
        "EditOp(offset=$offset, deleted=${deleted.length} chars, inserted=${inserted.length} chars)"

    private companion object {
        const val OVERHEAD_BYTES = 64L
    }
}

/**
 * The ops one undo step reverses, in the order they were applied. A typing
 * run, a compound edit (`beginGroup` / `endGroup`) or a single paste.
 */
public class EditGroup internal constructor() {
    private val edits = ArrayList<EditOp>()

    /** The ops, oldest first. */
    public val ops: List<EditOp> get() = edits

    internal var retainedBytes: Long = 0
        private set

    internal fun add(op: EditOp) {
        edits += op
        retainedBytes += op.retainedBytes
    }
}
