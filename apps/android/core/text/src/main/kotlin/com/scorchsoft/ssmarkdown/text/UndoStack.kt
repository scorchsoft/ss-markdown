package com.scorchsoft.ssmarkdown.text

/**
 * Undo and redo as a stack of [EditGroup]s, bounded by memory rather than
 * by step count: a thousand keystrokes cost less than one big paste.
 *
 * Grouping follows what a person thinks of as one action:
 * - **Typing runs.** Consecutive single-place inserts (or backspaces, or
 *   forward deletes) less than [typingRunMillis] apart join one group, so
 *   undo removes a burst of typing, not a letter.
 * - **Compound edits.** Everything between [beginGroup] and [endGroup] is
 *   one step (a toolbar "bold", a Preview round-trip, a 50 MB paste applied
 *   in chunks). Groups nest; only the outermost counts.
 *
 * When the budget is exceeded the oldest groups are dropped; the newest is
 * always kept, even if it alone is over budget, because losing the undo of
 * what you just did is worse than a little memory.
 */
@Suppress("TooManyFunctions") // Small, symmetric operations (undo/redo, begin/end) that only make sense together.
public class UndoStack(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val typingRunMillis: Long = DEFAULT_TYPING_RUN_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private val undoGroups = ArrayDeque<EditGroup>()
    private val redoGroups = ArrayDeque<EditGroup>()
    private var groupDepth = 0
    private var explicitGroup: EditGroup? = null
    private var typingRun: EditGroup? = null
    private var lastRecordedAt = Long.MIN_VALUE

    /** True when there is something to undo. */
    public val canUndo: Boolean get() = undoGroups.isNotEmpty()

    /** True when there is something to redo. */
    public val canRedo: Boolean get() = redoGroups.isNotEmpty()

    /**
     * Memory held by both stacks: what the [maxBytes] budget is checked
     * against. The memory governor budgets for the cap itself
     * ([DEFAULT_MAX_BYTES]) when it admits a file.
     */
    public var retainedBytes: Long = 0
        private set

    /** Records an op that has just been applied. Clears redo. */
    public fun record(op: EditOp) {
        if (op.isNoOp) return
        clearRedo()
        val now = clock()
        val group =
            if (groupDepth > 0) {
                explicitGroup ?: newGroup().also { explicitGroup = it }
            } else {
                typingRun?.takeIf { now - lastRecordedAt <= typingRunMillis && continuesRun(it.ops.last(), op) }
                    ?: newGroup()
            }
        group.add(op)
        retainedBytes += op.retainedBytes
        typingRun = if (groupDepth == 0 && op.isTypingStep()) group else null
        lastRecordedAt = now
        trimToBudget()
    }

    /** Starts a compound edit; every op until the matching [endGroup] is one undo step. */
    public fun beginGroup() {
        if (groupDepth++ == 0) {
            explicitGroup = null
            typingRun = null
        }
    }

    /** Ends the compound edit opened by the matching [beginGroup]. */
    public fun endGroup() {
        check(groupDepth > 0) { "endGroup without beginGroup" }
        if (--groupDepth == 0) explicitGroup = null
    }

    /** Ends the current typing run, e.g. because the cursor moved; the next keystroke starts a new step. */
    public fun breakTypingRun() {
        typingRun = null
    }

    /** Reverses the newest group through [apply] (newest op first) and returns it, or null if there is none. */
    public fun undo(apply: (EditOp) -> Unit): EditGroup? {
        check(groupDepth == 0) { "Cannot undo inside an open edit group" }
        val group = undoGroups.removeLastOrNull() ?: return null
        group.ops.asReversed().forEach { apply(it.inverse()) }
        redoGroups.addLast(group)
        typingRun = null
        return group
    }

    /** Re-applies the newest undone group through [apply] and returns it, or null if there is none. */
    public fun redo(apply: (EditOp) -> Unit): EditGroup? {
        check(groupDepth == 0) { "Cannot redo inside an open edit group" }
        val group = redoGroups.removeLastOrNull() ?: return null
        group.ops.forEach(apply)
        undoGroups.addLast(group)
        typingRun = null
        return group
    }

    /** [undo] applied straight to [text]. */
    public fun undo(text: PieceTree): EditGroup? = undo { it.applyTo(text) }

    /** [redo] applied straight to [text]. */
    public fun redo(text: PieceTree): EditGroup? = redo { it.applyTo(text) }

    /** Forgets everything, e.g. when the file is replaced from outside. */
    public fun clear() {
        undoGroups.clear()
        redoGroups.clear()
        explicitGroup = null
        typingRun = null
        retainedBytes = 0
    }

    private fun newGroup(): EditGroup = EditGroup().also { undoGroups.addLast(it) }

    private fun clearRedo() {
        redoGroups.forEach { retainedBytes -= it.retainedBytes }
        redoGroups.clear()
    }

    private fun trimToBudget() {
        while (retainedBytes > maxBytes && undoGroups.size > 1) {
            retainedBytes -= undoGroups.removeFirst().retainedBytes
        }
    }

    private fun EditOp.isTypingStep(): Boolean =
        (deleted.isEmpty() || inserted.isEmpty()) && deleted.length + inserted.length <= MAX_TYPING_STEP

    /** Does [op] carry on where [previous] left off: typing forward, backspacing, or deleting forward? */
    private fun continuesRun(
        previous: EditOp,
        op: EditOp,
    ): Boolean {
        val bothInserts = previous.deleted.isEmpty() && op.deleted.isEmpty()
        val bothDeletes = previous.inserted.isEmpty() && op.inserted.isEmpty()
        return when {
            bothInserts -> op.offset == previous.offset + previous.inserted.length
            bothDeletes -> op.offset + op.deleted.length == previous.offset || op.offset == previous.offset
            else -> false
        }
    }

    /** Defaults. */
    public companion object {
        /** 32 MB: generous on a 256 MB heap, and the governor can ask for less. */
        public const val DEFAULT_MAX_BYTES: Long = 32L * 1024 * 1024

        /** A pause longer than this ends a typing run. */
        public const val DEFAULT_TYPING_RUN_MILLIS: Long = 1_000

        /** Keyboards commit whole words at once, so a "keystroke" can be a few dozen chars. */
        private const val MAX_TYPING_STEP = 32
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
