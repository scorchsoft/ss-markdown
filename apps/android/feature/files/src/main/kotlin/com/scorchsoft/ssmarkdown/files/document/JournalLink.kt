package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.history.TextEdit
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.history.journal.CleanResult
import com.scorchsoft.ssmarkdown.history.journal.JournalHistory
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import com.scorchsoft.ssmarkdown.history.journal.JournalWriteResult
import com.scorchsoft.ssmarkdown.history.journal.JournalWriter
import com.scorchsoft.ssmarkdown.history.journal.OpenResult
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.Snapshot
import com.scorchsoft.ssmarkdown.text.UndoHistory
import java.io.IOException

/**
 * One document's connection to the recovery journal (robustness §1): a
 * journal exists while the document has unsaved edits, and, after a save,
 * while it has undo history worth keeping (a *clean* journal, ADR 0016).
 *
 * ### Undo history
 * Every run starts with the document's undo steps ([historySource]),
 * written after its header or snapshot, and the undo stack's decisions
 * (a step sealed, an undo or redo of n ops) are queued in order with the
 * ops ([stepSealed], [undoStep], [redoStep]). So the journal can rebuild
 * the stacks after a process death exactly as the user left them. While
 * an undo or redo is applying its ops, the run is never restarted
 * (no compaction, no resync): its ops must follow its marker.
 *
 * ### Saves
 * A verified save does not restart the run: it appends a Rebase record
 * naming the saved file ([saved]), synced before the base copy moves, so
 * the journal already holding the history and the ops simply carries on
 * from the new base (FORMAT.md, "Saves"; review r3 M3). Keeping the
 * history is best effort and guards no unsaved text: if that record
 * cannot be written, the old journal is deleted (it must never outlive
 * the save on the old base, r3 M1), the history is let go, and the next
 * edit starts a fresh journal on the saved file. None of that turns the
 * link unhealthy.
 *
 * ### Two halves, one order
 * Every public method is called on the document's owner thread (the main
 * thread) and does no I/O: it updates what the owner knows and **queues**
 * the file work on the [JournalExecutor]. The executor runs the queue in
 * order on its own thread, which alone touches the [JournalWriter]. So the
 * journal on disk follows exactly the order in which the owner saw edits,
 * saves and resolutions, whatever the disk is doing meanwhile; a keystroke
 * that lands while a save is suspended on I/O is queued after the save's
 * bookkeeping, never inside it (DS-1, DS-2, PERF-6, PERF-7).
 *
 * ### Never silently dropped
 * If the journal cannot run (the disk refused, a record was too large),
 * the link turns unhealthy, tells the owner through [onHealthChanged],
 * and starts again from a full snapshot of the text at the next edit or
 * [flush], at most once per [retryMillis]. It never returns quietly with an edit
 * unjournalled.
 *
 * ### A journal that is not ours
 * An earlier session's journal that recovery could not apply is *held*
 * ([hold]): the link never deletes it, and moves it into quarantine,
 * unread, only when this session starts a run of edits of its own (DS-3).
 */
@Suppress("TooManyFunctions") // The owner-side commands plus their queued halves: one small function each.
internal class JournalLink(
    private val store: JournalStore,
    private val key: String,
    private val executor: JournalExecutor,
    private val onHealthChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
) {
    // ---- Owner (main-thread) state. ----

    /** The base the running journal was written on; null when no run of edits is journalled. */
    private var journalBase: BaseFingerprint? = null

    /** An earlier session's journal sits in our file and must not be deleted. */
    private var held = false
    private var lastRetryAt = Long.MIN_VALUE

    /** Chars recorded since the run's last snapshot (or header): what replay must work through. */
    private var journalledChars = 0L

    /**
     * About how many bytes the run's records take since its last snapshot
     * (or header): each op's frame and offsets plus its chars, each marker's
     * frame. A save no longer restarts the run, so keystroke after keystroke
     * at about 48 bytes each would otherwise grow the file far past the
     * chars they carry before [journalledChars] asked for a compaction.
     */
    private var journalledBytes = 0L

    /** Ops of the undo or redo in progress still to come; the run must not restart until they have. */
    private var stepOpsLeft = 0

    /**
     * The running journal is clean (history only) and nothing has changed
     * the text since. Its first change is synced at once: until then the
     * file says "no unsaved edits", and a death right after an edit must
     * find the journal saying otherwise, as a freshly started journal's
     * header always did.
     */
    private var cleanRun = false

    /**
     * The document's undo steps, newest first up to the tail cap, read on
     * the owner thread whenever a run starts. Set by the [OpenDocument]
     * that owns this link.
     */
    var historySource: () -> UndoHistory = { UndoHistory.EMPTY }

    // ---- Journal-thread state. ----

    private var writer: JournalWriter? = null

    /**
     * Set when a save's history could not be kept and the journal was
     * dropped: the next record starts a fresh journal on this base (the
     * saved file, whose text that record's edit applies to), replacing a
     * journal on [restartReplacing] if one is still there.
     */
    private var restartOn: BaseFingerprint? = null
    private var restartReplacing: BaseFingerprint? = null

    /** The file holds a journal this link wrote (and so may replace or delete). */
    private var ownsFile = false

    @Volatile
    private var broken = false

    /** False once a journal write has failed; the edits are still in memory. Read on any thread. */
    @Volatile
    var healthy: Boolean = true
        private set

    /** True while a run of edits is being journalled. Owner thread. */
    val isRunning: Boolean get() = journalBase != null

    /** True while an earlier session's unapplied journal is held in place. Owner thread. */
    val isHolding: Boolean get() = held

    /**
     * Marks the journal file as an earlier session's that could not be
     * applied: kept until the user starts editing or chooses (DS-3).
     */
    fun hold() {
        held = true
    }

    /**
     * Takes over the journal already in the file: recovery replayed it, so
     * its edits are in the text and the next [resume] may replace it.
     */
    fun adopt() {
        held = false
        executor.execute { ownsFile = true }
    }

    /**
     * Called just before [op] is applied to a document whose text is
     * still [before], on [base]. [dirty] says whether [before] differs from
     * [base]'s text, in which case the run begins with a snapshot of it.
     */
    fun beforeEdit(
        op: EditOp,
        base: BaseFingerprint,
        dirty: Boolean,
        before: () -> Snapshot,
    ) {
        if (op.isNoOp) return
        if (stepOpsLeft > 0) {
            stepOpsLeft-- // Part of an undo or redo: its marker already started or checked the run.
        } else {
            startOrRetry(base, dirty, before)
        }
        val edit = PendingEdit.of(op)
        journalledChars += op.deleted.length + op.inserted.length
        journalledBytes += OP_RECORD_BYTES + op.deleted.length + op.inserted.length
        executor.execute { guarded { append(edit) } }
        syncIfFirstChange()
    }

    /**
     * Called after an op was applied, with the text's new length. When the
     * run's ops outgrow the text (more than [COMPACT_RATIO] times it, and at
     * least [COMPACT_MIN_CHARS] chars or [COMPACT_MIN_BYTES] of records), the
     * journal is replaced by one snapshot of
     * [after], so replay stays fast and the file stays small however long
     * the session (robustness §1, DS-11). It is the same atomic replace a
     * save uses, queued after the op it follows.
     */
    fun afterEdit(
        textLength: Int,
        after: () -> Snapshot,
    ) {
        val base = journalBase ?: return
        if (stepOpsLeft > 0 || broken) return
        val manyChars = journalledChars >= COMPACT_MIN_CHARS && journalledChars >= COMPACT_RATIO * textLength
        val manyBytes = journalledBytes >= COMPACT_MIN_BYTES && journalledBytes >= COMPACT_RATIO * textLength
        if (!manyChars && !manyBytes) return
        begin(base, after(), replacing = base)
    }

    /**
     * The undo stack sealed its newest step; the op that follows starts a
     * new one. Queued in order with the ops. With no run, there is nothing
     * to tell: a run that starts later writes the stack as it is then.
     */
    fun stepSealed() {
        if (!isRunning) return
        journalledBytes += MARKER_RECORD_BYTES
        executor.execute { guarded { record { it.endStep() } } }
    }

    /**
     * Undo is about to apply the inverse of a step of [opCount] ops: they
     * are queued after this marker. A run is started (or retried) here,
     * before the step moves, so its history still holds the step; see
     * [beforeEdit] for [base], [dirty] and [before].
     */
    fun undoStep(
        opCount: Int,
        base: BaseFingerprint,
        dirty: Boolean,
        before: () -> Snapshot,
    ) = step(opCount, base, dirty, before) { it.undoStep(opCount) }

    /** Redo is about to re-apply a step of [opCount] ops; as [undoStep]. */
    fun redoStep(
        opCount: Int,
        base: BaseFingerprint,
        dirty: Boolean,
        before: () -> Snapshot,
    ) = step(opCount, base, dirty, before) { it.redoStep(opCount) }

    /**
     * Starts the run afresh from [text] on [base], in one atomic replace
     * of whatever journal this link was running: after recovery, after a
     * save the user typed through, and after a resolved conflict.
     * [replacing] is the base of the journal being replaced, if this link
     * wrote one.
     */
    fun resume(
        base: BaseFingerprint,
        text: Snapshot,
        replacing: BaseFingerprint? = journalBase,
    ) = begin(base, text, replacing)

    /**
     * Starts the run afresh as a *clean* journal on [base]: the text is
     * exactly [base]'s, so the journal holds only the undo history. What
     * Save as does for its target, and what a clean open that restored
     * history does to keep it. In one atomic replace, like [resume].
     *
     * Best effort: a clean journal guards no unsaved text, so if it cannot
     * be written the history is let go, no journal on another base is left
     * behind, the link stays healthy, and the next edit starts a journal
     * on [base] (review r3 M1, L4).
     */
    fun resumeClean(
        base: BaseFingerprint,
        replacing: BaseFingerprint? = journalBase,
    ) = begin(base, null, replacing, clean = true)

    /**
     * A verified save wrote exactly the text, as [base]; the document has
     * undo history worth keeping (ADR 0016). The running journal already
     * holds that history and every op since, so it is kept and a Rebase
     * record is appended and synced: 66 bytes, where a rewrite was up to
     * 8 MB and a capture of the whole undo stack on this thread (review r3
     * M3). A broken or held run, or none, is replaced by a clean journal
     * instead ([resumeClean], best effort).
     */
    fun saved(base: BaseFingerprint) {
        val old = journalBase
        if (old == null || !appendable) {
            resumeClean(base, replacing = old)
            return
        }
        journalBase = base
        cleanRun = true
        executor.execute { guarded { rebase(old, base) } }
    }

    /**
     * Makes everything durable now: on pause and before any risky step.
     * A run that broke is started again here from [current], the text as
     * it is now, once [retryMillis] have passed, so edits typed and then
     * left alone are journalled when the disk recovers, not only at the
     * next keystroke (R2-DS-8).
     */
    fun flush(current: (() -> Snapshot)? = null) {
        if (!isRunning) return
        if (current != null && broken && clock() - lastRetryAt >= retryMillis) resync(current())
        executor.execute { guarded { flushNow() } }
    }

    /**
     * Ends the run after a verified save or a recorded discard. Only a
     * journal this link wrote, on the base it wrote it on, is deleted; a
     * held one is left exactly where it is.
     */
    fun endClean() {
        val expected = journalBase ?: return
        journalBase = null
        broken = false
        executor.execute { guarded { finish(expected) } }
    }

    /** Releases the file but keeps it: the document is closing dirty or the app is going away. */
    fun close() {
        journalBase = null
        executor.execute { guarded { release() } }
    }

    /**
     * The user chose to drop the held journal ("Discard them"): it is
     * moved into quarantine, never deleted outright.
     */
    fun discardHeld() {
        if (!held) return
        held = false
        executor.execute { guarded { store.setAside(key) } }
    }

    // ---- Owner-side helpers. ----

    /** The running journal is ours, sound, and between steps: a record can go on its end. */
    private val appendable: Boolean get() = !broken && !held && stepOpsLeft == 0

    private fun startOrRetry(
        base: BaseFingerprint,
        dirty: Boolean,
        before: () -> Snapshot,
    ) {
        when {
            !isRunning -> begin(base, if (dirty) before() else null, replacing = null)
            broken && clock() - lastRetryAt >= retryMillis -> resync(before())
            else -> Unit
        }
    }

    private fun step(
        opCount: Int,
        base: BaseFingerprint,
        dirty: Boolean,
        before: () -> Snapshot,
        write: (JournalWriter) -> JournalWriteResult,
    ) {
        startOrRetry(base, dirty, before)
        stepOpsLeft = opCount
        journalledBytes += MARKER_RECORD_BYTES + Int.SIZE_BYTES
        executor.execute { guarded { record(write) } }
        syncIfFirstChange()
    }

    private fun syncIfFirstChange() {
        if (!cleanRun) return
        cleanRun = false
        executor.execute { guarded { flushNow() } }
    }

    private fun begin(
        base: BaseFingerprint,
        text: Snapshot?,
        replacing: BaseFingerprint?,
        clean: Boolean = false,
    ) {
        val setAside = held
        held = false
        journalBase = base
        broken = false
        journalledChars = 0
        journalledBytes = 0
        cleanRun = clean
        lastRetryAt = clock()
        val history = PendingHistory.of(historySource())
        executor.execute {
            if (clean) {
                bestEffort(base, replacing) { start(base, null, replacing, setAside, history, clean = true) }
            } else {
                guarded { start(base, text, replacing, setAside, history, clean = false) }
            }
        }
    }

    private fun resync(before: Snapshot) {
        val base = journalBase ?: return
        begin(base, before, replacing = base)
    }

    // ---- Journal-thread work. ----

    @Suppress("LongParameterList") // One queued step: everything begin() decided on the owner thread.
    private fun start(
        base: BaseFingerprint,
        text: Snapshot?,
        replacing: BaseFingerprint?,
        setAside: Boolean,
        pending: PendingHistory,
        clean: Boolean,
    ) {
        writer?.close()
        writer = null
        restartOn = null
        if (setAside || (!ownsFile && store.fileFor(key).exists())) store.setAside(key)
        val history = pending.toJournalHistory()
        val result =
            if (text == null && !clean) {
                store.start(key, base, history)
            } else {
                store.resume(key, base, text?.toString(), replacing.takeIf { ownsFile }, history)
            }
        if (result is OpenResult.Opened) {
            writer = result.writer
            ownsFile = true
            broken = false
            setHealthy(true)
        } else if (clean) {
            throw IOException("clean journal not written: $result")
        } else {
            breakRun()
        }
    }

    /**
     * The journal-thread half of [saved]: the Rebase record, synced at
     * once, because the base copy of [old] is replaced right after this
     * step and a journal still on [old] would then name bytes that exist
     * nowhere.
     */
    private fun rebase(
        old: BaseFingerprint,
        saved: BaseFingerprint,
    ) = bestEffort(saved, replacing = old) {
        val running = writer ?: throw IOException("no journal to rebase")
        val written =
            running.rebase(saved) is JournalWriteResult.Written && running.flush() is JournalWriteResult.Written
        if (!written) throw IOException("rebase not written")
        setHealthy(true)
    }

    /**
     * Runs [block], which writes nothing but undo history onto [base], a
     * file that holds the whole text. Any failure lets the history go
     * rather than the link's health: whatever journal this link had is
     * deleted (it may sit on [replacing], a base the file no longer has),
     * and the next record starts a fresh journal on [base] (review r3 M1).
     */
    @Suppress("TooGenericExceptionCaught") // As guarded(): the boundary of the journal thread.
    private inline fun bestEffort(
        base: BaseFingerprint,
        replacing: BaseFingerprint?,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (_: Exception) {
            dropAfterSave(base, replacing)
        } catch (_: OutOfMemoryError) {
            dropAfterSave(base, replacing)
        }
    }

    private fun dropAfterSave(
        base: BaseFingerprint,
        replacing: BaseFingerprint?,
    ) {
        runCatching { writer?.close() }
        writer = null
        // Ours, whatever base its header or last Rebase names: nothing in it is unsaved any more.
        val gone =
            !ownsFile ||
                when (runCatching { store.markClean(key) }.getOrNull()) {
                    CleanResult.Deleted, CleanResult.NothingToClean, is CleanResult.Quarantined -> true
                    else -> false
                }
        if (gone) ownsFile = false
        restartOn = base
        restartReplacing = if (gone) null else replacing
        broken = false
        setHealthy(true)
    }

    /** The first record after a dropped save journal: a fresh journal on the saved file, then the record. */
    private fun restart(): JournalWriter? {
        val base = restartOn ?: return null
        restartOn = null
        val result = store.resume(key, base, null, restartReplacing.takeIf { ownsFile }, JournalHistory.EMPTY)
        restartReplacing = null
        if (result !is OpenResult.Opened) return null
        ownsFile = true
        writer = result.writer
        return result.writer
    }

    private fun append(edit: PendingEdit) = record { it.append(edit.toTextEdit()) }

    /** Writes one record through [write]; any trouble is handled as an append's would be. */
    private fun record(write: (JournalWriter) -> JournalWriteResult) {
        val running = writer ?: restart()
        if (running == null) {
            breakRun()
            return
        }
        when (write(running)) {
            is JournalWriteResult.Written -> Unit

            // Still held in memory; the next flush retries it. The journal is behind, not wrong.
            is JournalWriteResult.Failed -> setHealthy(false)

            // Not recorded: the journal no longer matches the text. Start again from a snapshot.
            JournalWriteResult.TooLarge, JournalWriteResult.Closed -> breakRun()
        }
    }

    private fun flushNow() {
        val result = writer?.flush() ?: return
        setHealthy(result is JournalWriteResult.Written && !broken)
    }

    private fun finish(expected: BaseFingerprint) {
        writer?.close()
        writer = null
        restartOn = null
        if (!ownsFile) return
        when (store.markClean(key, expected)) {
            CleanResult.Deleted, CleanResult.NothingToClean, is CleanResult.Quarantined -> ownsFile = false
            else -> Unit
        }
        setHealthy(true)
    }

    private fun release() {
        writer?.close()
        writer = null
        restartOn = null
    }

    private fun breakRun() {
        broken = true
        writer?.close()
        writer = null
        setHealthy(false)
    }

    private fun setHealthy(value: Boolean) {
        if (healthy == value) return
        healthy = value
        onHealthChanged()
    }

    /** Journal work never takes the thread down: an unexpected failure is an unhealthy journal. */
    @Suppress("TooGenericExceptionCaught") // The boundary of the journal thread: anything is a failed write.
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            breakRun()
        } catch (_: OutOfMemoryError) {
            breakRun()
        }
    }

    /**
     * An edit queued for the journal thread. Its texts are immutable (a
     * removed range is normally a zero-copy snapshot slice), so the
     * strings are built on the journal thread, not during the keystroke.
     */
    private class PendingEdit(
        val offset: Int,
        val deleted: CharSequence,
        val inserted: CharSequence,
    ) {
        fun toTextEdit() = TextEdit(offset, deleted.toString(), inserted.toString())

        companion object {
            fun of(op: EditOp) = PendingEdit(op.offset, frozen(op.deleted), frozen(op.inserted))
        }
    }

    /**
     * The undo steps as the owner saw them when a run began. Captured on
     * the owner thread (the stack moves on) as the stack's own export,
     * whose lists are copies and whose ops are immutable; the strings are
     * built on the journal thread, like [PendingEdit]. An op whose text is
     * not already immutable (a String or a Snapshot) is copied here, but
     * no per-op object is made for the rest: a full tail is about 600,000
     * keystroke ops (review r3 M3).
     */
    private class PendingHistory(
        val history: UndoHistory,
    ) {
        fun toJournalHistory() =
            JournalHistory(
                history.undo.map { step -> step.map(::textEdit) },
                history.redo.map { step -> step.map(::textEdit) },
                history.newestOpen,
            )

        companion object {
            fun of(history: UndoHistory): PendingHistory {
                val frozen = history.undo.all(::allFrozen) && history.redo.all(::allFrozen)
                return PendingHistory(if (frozen) history else history.frozenCopy())
            }

            private fun allFrozen(step: List<EditOp>) = step.all { isFrozen(it.deleted) && isFrozen(it.inserted) }

            private fun UndoHistory.frozenCopy() =
                UndoHistory(
                    undo.map { step -> step.map(::frozenOp) },
                    redo.map { step -> step.map(::frozenOp) },
                    newestOpen,
                )

            private fun frozenOp(op: EditOp) = EditOp(op.offset, frozen(op.deleted), frozen(op.inserted))

            private fun textEdit(op: EditOp) = TextEdit(op.offset, op.deleted.toString(), op.inserted.toString())
        }
    }

    private companion object {
        const val DEFAULT_RETRY_MILLIS = 2_000L

        /** Compaction waits for at least this much journalled text, so small files are never rewritten per key. */
        const val COMPACT_MIN_CHARS = 1L shl 20

        /** Compact once the journalled text is this many times the document. */
        const val COMPACT_RATIO = 2L

        /**
         * Compaction also comes once the run's records pass this many bytes
         * (and twice the text): a run of one-char keystrokes is about 48
         * bytes each, so this bounds a journal that outlives saves to about
         * 16 MiB of ops plus its snapshot and 8 MB of history (review r3 M3).
         */
        const val COMPACT_MIN_BYTES = 16L shl 20

        /** An Op record's frame (17 bytes) and fixed fields (12), before its chars. */
        const val OP_RECORD_BYTES = 29L

        /** A step-end marker record: a frame and the kind byte. */
        const val MARKER_RECORD_BYTES = 18L

        /** True when [text] is safe to read later on another thread: snapshots and strings are. */
        fun isFrozen(text: CharSequence): Boolean = text is String || text is Snapshot

        /** [text] safe to read later on another thread: snapshots and strings are; anything else is copied. */
        fun frozen(text: CharSequence): CharSequence = if (isFrozen(text)) text else text.toString()
    }
}
