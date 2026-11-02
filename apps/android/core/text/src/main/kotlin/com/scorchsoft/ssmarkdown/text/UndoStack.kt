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
 *
 * **Its decisions are reported, not re-derived.** A [listener] hears when
 * a step is sealed and when a step is undone or redone, at the moment the
 * stack decides it, so the recovery journal can write exactly the steps
 * the user saw and rebuild them after a process death without ever
 * replaying the clock (docs/adr/0016-undo-survives-process-death.md).
 * [exportTail] and [restore] carry the stacks across that death as data.
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

    /** True while the newest undo group may still grow: an op joined it and no seal was reported since. */
    private var unsealed = false

    /** The grouping [prepare] decided for the op about to be recorded. */
    private var prepared: Prepared? = null

    /** Hears the stack's decisions; see [UndoListener]. */
    public var listener: UndoListener? = null

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

    /**
     * Records an op that has just been applied. Clears redo. When [prepare]
     * was called for [op] first, the grouping it decided is used.
     */
    public fun record(op: EditOp) {
        if (op.isNoOp) return
        val decided = prepared?.takeIf { it.op === op } ?: decide(op)
        prepared = null
        clearRedo()
        val group =
            decided.group ?: newGroup().also {
                if (groupDepth > 0) explicitGroup = it
            }
        group.add(op)
        retainedBytes += op.retainedBytes
        typingRun = if (groupDepth == 0 && op.isTypingStep()) group else null
        lastRecordedAt = decided.at
        unsealed = true
        trimToBudget()
    }

    /**
     * Decides, before [op] is applied, which step it joins, and reports a
     * seal through [listener] if it starts a new one. [TextDocument] calls
     * this so an observer hears the seal *before* the op that follows it,
     * which is the order the journal must write them in. [record] then
     * uses this decision.
     */
    internal fun prepare(op: EditOp) {
        prepared = if (op.isNoOp) null else decide(op)
    }

    private fun decide(op: EditOp): Prepared {
        val now = clock()
        val joins =
            if (groupDepth > 0) {
                explicitGroup
            } else {
                typingRun?.takeIf { now - lastRecordedAt <= typingRunMillis && continuesRun(it.ops.last(), op) }
            }
        if (joins == null && unsealed) {
            unsealed = false
            listener?.onStepSealed()
        }
        return Prepared(op, joins, now)
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

    /**
     * Reverses the newest group through [apply] (newest op first) and
     * returns it, or null if there is none. [listener] hears it before the
     * first op is applied, while the stacks still say what they said.
     */
    public fun undo(apply: (EditOp) -> Unit): EditGroup? {
        check(groupDepth == 0) { "Cannot undo inside an open edit group" }
        val group = undoGroups.lastOrNull() ?: return null
        listener?.onUndo(group.ops.size)
        // Moved before it is applied, so anyone who looks at the stacks
        // mid-step sees the step where it ends up, never in neither stack.
        undoGroups.removeLast()
        redoGroups.addLast(group)
        typingRun = null
        unsealed = false
        group.ops.asReversed().forEach { apply(it.inverse()) }
        return group
    }

    /** Re-applies the newest undone group through [apply] and returns it, or null if there is none. */
    public fun redo(apply: (EditOp) -> Unit): EditGroup? {
        check(groupDepth == 0) { "Cannot redo inside an open edit group" }
        val group = redoGroups.lastOrNull() ?: return null
        listener?.onRedo(group.ops.size)
        redoGroups.removeLast()
        undoGroups.addLast(group)
        typingRun = null
        unsealed = false
        group.ops.forEach(apply)
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
        unsealed = false
        prepared = null
        retainedBytes = 0
    }

    /**
     * The newest steps, as data, for the journal to keep across a process
     * death: undo steps from the newest back, then redo steps from the
     * next one to redo, while their [EditOp.tailBytes] fit in [maxBytes].
     * A step that does not fit ends that side of the tail, so the tail is
     * always the newest steps, never a sample with holes in it.
     *
     * The lists are copies and the ops are immutable, so the result may be
     * read on another thread.
     */
    public fun exportTail(maxBytes: Long = HISTORY_TAIL_MAX_BYTES): UndoHistory {
        var budget = maxBytes
        val undo = ArrayList<List<EditOp>>()
        for (group in undoGroups.asReversed()) {
            val cost = group.tailBytes()
            if (cost > budget) break
            budget -= cost
            undo += group.ops.toList()
        }
        val redo = ArrayList<List<EditOp>>()
        for (group in redoGroups.asReversed()) {
            val cost = group.tailBytes()
            if (cost > budget) break
            budget -= cost
            redo += group.ops.toList()
        }
        undo.reverse()
        redo.reverse()
        // "Still open" only means something if the newest group made it into the tail.
        val newestOpen = unsealed && undo.isNotEmpty() && undo.size == undoGroups.size
        return UndoHistory(undo, redo, newestOpen)
    }

    /**
     * Replaces both stacks with [history]: what [exportTail] kept before a
     * process death, rebuilt by the journal. Every step comes back sealed,
     * as a trailing open step is after a restart: the next keystroke
     * starts a new one. Not inside an open edit group.
     */
    public fun restore(history: UndoHistory) {
        check(groupDepth == 0) { "Cannot restore inside an open edit group" }
        clear()
        history.undo.filter { it.isNotEmpty() }.forEach { undoGroups.addLast(EditGroup.of(it)) }
        history.redo.filter { it.isNotEmpty() }.forEach { redoGroups.addLast(EditGroup.of(it)) }
        retainedBytes = undoGroups.sumOf { it.retainedBytes } + redoGroups.sumOf { it.retainedBytes }
        trimToBudget()
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

    private fun EditGroup.tailBytes(): Long = ops.sumOf { it.tailBytes }

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

        /**
         * 8 MB: how much undo history the recovery journal keeps on disk
         * across a process death, newest steps first (ADR 0016), counted
         * as [EditOp.tailBytes]. The in-memory stack keeps up to
         * [DEFAULT_MAX_BYTES]; after a restart, steps beyond this tail are gone.
         */
        public const val HISTORY_TAIL_MAX_BYTES: Long = 8L * 1024 * 1024

        /** A pause longer than this ends a typing run. */
        public const val DEFAULT_TYPING_RUN_MILLIS: Long = 1_000

        /** Keyboards commit whole words at once, so a "keystroke" can be a few dozen chars. */
        private const val MAX_TYPING_STEP = 32
        private const val NANOS_PER_MILLI = 1_000_000L
    }

    /** What [prepare] decided for one op: the group it joins (null for a new one) and when. */
    private class Prepared(
        val op: EditOp,
        val group: EditGroup?,
        val at: Long,
    )
}

/**
 * Hears the decisions an [UndoStack] makes, in the order it makes them.
 * [TextDocument] forwards them to its [EditObserver]s.
 */
public interface UndoListener {
    /** The newest step is closed: the op about to be applied starts a new one. */
    public fun onStepSealed()

    /** The newest step, of [opCount] ops, is about to be undone; its inverse ops follow, newest first. */
    public fun onUndo(opCount: Int)

    /** The next undone step, of [opCount] ops, is about to be redone; its ops follow, in order. */
    public fun onRedo(opCount: Int)
}

/**
 * Both undo stacks as plain data: what [UndoStack.exportTail] hands the
 * journal and [UndoStack.restore] takes back.
 *
 * @property undo the undo steps, oldest first; each step's ops in the order they were applied.
 * @property redo the redo steps, the next one to redo last; each step's ops in the order they were applied.
 * @property newestOpen true when the newest undo step may still grow (a typing run or an open
 *   compound edit), so an op recorded after this export joins it unless a seal is reported first.
 */
public class UndoHistory(
    public val undo: List<List<EditOp>>,
    public val redo: List<List<EditOp>>,
    public val newestOpen: Boolean = false,
) {
    /** True when there is nothing to undo or redo. */
    public val isEmpty: Boolean get() = undo.isEmpty() && redo.isEmpty()

    /** The empty history. */
    public companion object {
        /** Nothing to undo or redo. */
        public val EMPTY: UndoHistory = UndoHistory(emptyList(), emptyList())
    }
}
