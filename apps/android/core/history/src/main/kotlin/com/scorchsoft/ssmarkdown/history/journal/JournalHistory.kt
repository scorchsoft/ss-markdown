package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit

/**
 * A document's undo and redo steps as the journal keeps them across a
 * process death (FORMAT.md record type 4, ADR 0016). Plain data: the
 * history module does not know the text engine, so a step is a list of
 * [TextEdit]s, oldest first, exactly as they were applied.
 *
 * Undoing a step applies the inverse of its edits, newest first; redoing
 * it applies them again in order. The editor's undo stack exports and
 * restores the same shape.
 *
 * @property undo the undo steps, oldest first.
 * @property redo the redo steps, the next one to redo last.
 * @property newestOpen true when the newest undo step may still grow: an edit written after
 *   this history joins it unless a step-end marker comes first.
 */
public data class JournalHistory(
    public val undo: List<List<TextEdit>>,
    public val redo: List<List<TextEdit>>,
    public val newestOpen: Boolean = false,
) {
    /** True when there is nothing to undo or redo. */
    public val isEmpty: Boolean get() = undo.isEmpty() && redo.isEmpty()

    /**
     * The newest steps that fit in [maxBytes]: undo steps from the newest
     * back, then redo steps from the next one to redo, each side ending at
     * the first step that does not fit. The same rule as the editor's
     * export, so a tail read back is the tail that was written.
     */
    public fun trimmedTo(maxBytes: Long = TAIL_MAX_BYTES): JournalHistory {
        var budget = maxBytes
        val keptUndo = newestThatFit(undo, budget).also { kept -> budget -= kept.sumOf(::cost) }
        val keptRedo = newestThatFit(redo, budget)
        if (keptUndo.size == undo.size && keptRedo.size == redo.size) return this
        return JournalHistory(keptUndo, keptRedo, newestOpen && keptUndo.size == undo.size)
    }

    /** The format's limits. */
    public companion object {
        /** Nothing to undo or redo. */
        public val EMPTY: JournalHistory = JournalHistory(emptyList(), emptyList())

        /**
         * 8 MB: the most undo history a journal keeps, newest steps first.
         * Each edit counts [tailBytes]. Matches the editor's
         * `UndoStack.HISTORY_TAIL_MAX_BYTES`.
         */
        public const val TAIL_MAX_BYTES: Long = 8L * 1024 * 1024

        private const val FIXED_BYTES = 12L

        /** What [edit] counts for against [TAIL_MAX_BYTES]: UTF-16 bytes of both texts, plus 12. */
        public fun tailBytes(edit: TextEdit): Long = 2L * (edit.deleted.length + edit.inserted.length) + FIXED_BYTES

        private fun cost(step: List<TextEdit>): Long = step.sumOf(::tailBytes)

        /** The newest steps of [steps] (oldest first in the result) whose costs add up to at most [budget]. */
        private fun newestThatFit(
            steps: List<List<TextEdit>>,
            budget: Long,
        ): List<List<TextEdit>> {
            var left = budget
            var first = steps.size
            while (first > 0 && cost(steps[first - 1]) <= left) {
                left -= cost(steps[first - 1])
                first--
            }
            return steps.subList(first, steps.size).toList()
        }
    }
}

/**
 * Rebuilds a [JournalHistory] from records, following the editor's undo
 * stack decision for decision (FORMAT.md, "Undo history"):
 *
 * - an edit joins the newest undo step while it is open, else starts one,
 *   and clears redo;
 * - a step end closes the newest step;
 * - an undo or redo marker closes it too, and says how many of the edits
 *   that follow are that undo or redo rather than new edits;
 * - a snapshot forgets the history (one written by this format is
 *   followed by its History record); a History record replaces it.
 *
 * A trailing open step counts as closed, as it does in a restarted
 * editor. An undo or redo the file ends in the middle of is rolled back
 * by the caller ([unfinished]), so text and history agree.
 */
internal class HistoryBuilder {
    private val undo = ArrayList<List<TextEdit>>()
    private val redo = ArrayList<List<TextEdit>>()
    private var open: MutableList<TextEdit>? = null
    private var stepKind: StepKind? = null
    private var stepLeft = 0
    private val stepEdits = ArrayList<TextEdit>()

    /** True while the edits of an undo or redo are still expected. */
    val inStep: Boolean get() = stepKind != null

    /** The edits of an undo or redo that never finished, in the order they were applied. */
    val unfinished: List<TextEdit> get() = if (inStep) stepEdits.toList() else emptyList()

    fun edit(edit: TextEdit) {
        if (stepKind != null) {
            stepEdits += edit
            if (--stepLeft == 0) finishStep()
            return
        }
        redo.clear()
        val step = open ?: ArrayList<TextEdit>().also { undo += it }
        step += edit
        open = step
    }

    fun step(
        kind: StepKind,
        opCount: Int,
    ) {
        open = null
        if (kind == StepKind.End) return
        stepKind = kind
        stepLeft = opCount
        stepEdits.clear()
        if (opCount == 0) finishStep()
    }

    fun replace(history: JournalHistory) {
        undo.clear()
        redo.clear()
        undo += history.undo
        redo += history.redo
        open =
            if (history.newestOpen &&
                undo.isNotEmpty()
            ) {
                undo.last().toMutableList().also { undo[undo.lastIndex] = it }
            } else {
                null
            }
    }

    fun reset() = replace(JournalHistory.EMPTY)

    /** Forgets an unfinished undo or redo; the caller has rolled its edits back. */
    fun dropUnfinished() {
        stepKind = null
        stepEdits.clear()
    }

    fun result(): JournalHistory =
        JournalHistory(undo.map { it.toList() }, redo.toList(), newestOpen = false).trimmedTo()

    private fun finishStep() {
        val kind = stepKind
        stepKind = null
        val applied = stepEdits.toList()
        stepEdits.clear()
        when (kind) {
            // The edits were the inverses, newest first: the step is their inverses, oldest first.
            StepKind.Undo -> {
                undo.removeLastOrNull()
                redo += applied.asReversed().map(TextEdit::inverted)
            }

            StepKind.Redo -> {
                redo.removeLastOrNull()
                undo += applied
            }

            else -> {
                Unit
            }
        }
    }
}
