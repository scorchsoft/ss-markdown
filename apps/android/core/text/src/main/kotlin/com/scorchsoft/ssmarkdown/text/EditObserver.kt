package com.scorchsoft.ssmarkdown.text

/**
 * Hears every op a [TextDocument] applies, whatever applied it: a typed
 * key, a command, undo or redo.
 *
 * It exists for the recovery journal (docs/robustness.md §1). The journal
 * must record exactly the chars that changed, before anything else can
 * happen, and it must never depend on every caller remembering to report
 * its edit. Hooking the one place a document changes gives it both: an
 * op arrives here with its exact offset, the removed text (a zero-copy
 * [Snapshot] slice) and the inserted text, whether the view, a command or
 * the undo stack applied it.
 *
 * Called on the document's own thread, synchronously, so an observer must
 * be quick: copy what it needs and hand the work on.
 */
public interface EditObserver {
    /**
     * [op] is about to be applied: the text still holds [EditOp.deleted]
     * at [EditOp.offset]. This is the moment to capture "the text before".
     */
    public fun beforeEdit(op: EditOp): Unit = Unit

    /** [op] has been applied and [TextDocument.version] has moved. */
    public fun afterEdit(op: EditOp): Unit = Unit

    /**
     * The undo stack sealed its newest step: the op told next starts a
     * new one. Told before that op's [beforeEdit]. Only an observer that
     * keeps the undo history across a restart (the journal) needs it.
     */
    public fun onStepSealed(): Unit = Unit

    /**
     * Undo is about to apply the inverse of a step of [opCount] ops: the
     * next [opCount] ops told are that undo, not new edits.
     */
    public fun onUndo(opCount: Int): Unit = Unit

    /** Redo is about to re-apply a step of [opCount] ops: the next [opCount] ops told are that redo. */
    public fun onRedo(opCount: Int): Unit = Unit
}
