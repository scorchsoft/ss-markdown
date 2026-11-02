package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.memory.MemoryGovernor
import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore
import com.scorchsoft.ssmarkdown.files.save.FileIntegrity
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.files.save.SaveTimeout
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor

/**
 * Every open document, one entry per URI, for the life of the process
 * (robustness §5a). Windows bind to an [OpenDocument]; they never own
 * text, so rotation, folding or a second window cannot lose any.
 *
 * Call it from one thread (the main thread in the app). Its suspend
 * functions hop to [io] for every byte of I/O and come back, so the
 * caller's thread never blocks on a slow provider. Each document's
 * open, save, check and resolve steps are serialised by its own lock.
 *
 * Startup order: [scanForRecovery] once, before anything opens.
 *
 * [saveTimeout] bounds each save; a slow provider ends as
 * [SaveOutcome.TimedOut] with the file untouched or restored.
 *
 * Journal writes run on [journalThread], never on the caller's thread
 * (see [JournalExecutor]); [callbacks] runs the store's notifications
 * from that thread back on the owner's (the main executor in the app).
 * The defaults run both inline, for tests and tools.
 */
@Suppress(
    "LongParameterList", // Constructor injection without a DI framework (docs/engineering.md).
    "TooManyFunctions", // A facade: each member is one line delegating to a small flow class.
)
class DocumentStore(
    provider: DocumentProvider,
    journals: JournalStore,
    baseCopies: BaseCopyStore,
    governor: MemoryGovernor,
    io: CoroutineDispatcher,
    history: HistoryHook = HistoryHook.NONE,
    cursorMapper: CursorMapper = CursorMapper.BY_LINE,
    saveTimeout: SaveTimeout = SaveTimeout(),
    journalThread: JournalExecutor = JournalExecutor.INLINE,
    callbacks: Executor = Executor(Runnable::run),
) {
    private val context =
        StoreContext(
            provider,
            journals,
            baseCopies,
            governor,
            io,
            history,
            cursorMapper,
            saveTimeout,
            journalThread,
            callbacks,
        )
    private val opener = DocumentOpener(context)
    private val changes = OutsideChanges(context)
    private val saves = SaveCoordinator(context, changes)
    private val openLock = Mutex()
    private val copies = RecoveryCopies(context, ::open, ::save)

    /** Every open document, in opening order. */
    val openDocuments: List<OpenDocument> get() = context.entries.values.toList()

    /** Open documents with unsaved edits: the "n files unsaved · Save all" row. */
    val dirtyDocuments: List<OpenDocument> get() = context.entries.values.filter { it.isDirty }

    /** The open entry for [uri], if any. */
    operator fun get(uri: DocumentUri): OpenDocument? = context.entries[uri]

    /** Subscribes to [DocumentEvent]s; delivered on the caller's thread. */
    fun addListener(listener: (DocumentEvent) -> Unit) {
        context.listeners += listener
    }

    /** Unsubscribes. */
    fun removeListener(listener: (DocumentEvent) -> Unit) {
        context.listeners -= listener
    }

    /**
     * The launch scan: every document with unsaved edits from an earlier
     * session. Opening one restores them. First, every save the last
     * process died in the middle of is settled ([InterruptedSaves]): a
     * torn file is put back to its old bytes, and a journal left on the
     * version a completed save replaced is moved to the saved one. Base
     * copies that no journal or unsettled save needs are cleared on the way.
     */
    suspend fun scanForRecovery(): RecoveryScan =
        withContext(context.io) {
            val repaired = InterruptedSaves(context).settleAll()
            val scan = context.journals.scan()
            val offers = scan.unclean.map { RecoveryOffer(DocumentUri(it.documentKey), it.createdAtMillis, it.base) }
            offers.forEach { context.journalTimes[it.uri] = it.editedAtMillis }
            scan.historyOnly.forEach { context.unclaimedHistory[DocumentUri(it.documentKey)] = it.base }
            context.baseCopies.retainOnly(offers.map { it.uri } + context.entries.keys)
            RecoveryScan(offers, scan.unsupported.size, scan.quarantined.size, scan.failures.size, repaired)
        }

    /**
     * Deletes the clean journals the launch scan found whose documents
     * were not opened since: undo history is kept while a document is
     * open (ADR 0016), and one the launch did not reopen is closed. Call
     * once the launch's opens have been asked for; it waits behind any
     * open in progress, so a document being reopened keeps its history.
     */
    suspend fun releaseUnclaimedHistory() {
        val stale =
            openLock.withLock {
                context.unclaimedHistory.filterKeys { it !in context.entries }.also { context.unclaimedHistory.clear() }
            }
        if (stale.isEmpty()) return
        context.journalThread.call {
            stale.forEach { (uri, base) ->
                if (context.journals.holdsHistoryOnly(uri.value)) context.journals.markClean(uri.value, base)
            }
        }
    }

    /** Opens [uri], or joins the entry already open for it (a second window gets the same buffer). */
    suspend fun open(uri: DocumentUri): OpenOutcome =
        openLock.withLock {
            val existing = context.entries[uri]
            if (existing != null) {
                existing.windows++
                OpenOutcome.Opened(existing)
            } else {
                opener.open(uri)
            }
        }

    /** Saves [uri] through the safe pipeline; see [SaveOutcome]. */
    suspend fun save(uri: DocumentUri): SaveOutcome {
        val entry = context.entries[uri] ?: return SaveOutcome.Missing(FileIntegrity.Untouched)
        // Once a save holds the lock it runs to the end, bookkeeping included: a write that
        // completed must never skip marking the buffer clean and settling the journal (DS-5).
        return entry.lock.withLock { withContext(NonCancellable) { saves.save(entry) } }
    }

    /**
     * Save as: writes [uri]'s buffer to [target] and moves the entry there.
     * [SaveOutcome.TargetOpen] if [target] is open here with anything to
     * lose (R2-DS-4). Holds the open lock throughout, so the target cannot
     * be opened while it is being written.
     */
    suspend fun saveAs(
        uri: DocumentUri,
        target: DocumentUri,
    ): SaveOutcome {
        val entry = context.entries[uri] ?: return SaveOutcome.Missing(FileIntegrity.Untouched)
        return openLock.withLock {
            entry.lock.withLock { withContext(NonCancellable) { saves.saveAs(entry, target) } }
        }
    }

    /** Looks for an outside change now: on resume, on a timer, on a `ContentObserver` ping. */
    suspend fun checkOutside(
        uri: DocumentUri,
        reason: CheckReason,
    ): OutsideChange {
        val entry = context.entries[uri] ?: return OutsideChange.NotOpen
        return entry.lock.withLock { changes.check(entry, reason) }
    }

    /**
     * "Keep the file as it is". Mine goes to History first; if History
     * could not keep it the answer is [ResolveOutcome.NotKept] and nothing
     * changes, unless [evenIfNotKept] (the user accepted losing mine).
     */
    suspend fun keepTheirs(
        uri: DocumentUri,
        evenIfNotKept: Boolean = false,
    ): ResolveOutcome = resolving(uri) { changes.keepTheirs(it, evenIfNotKept) }

    /**
     * "Merge my edits" with a merge the user settled by hand: [text] was
     * made from the buffer at [madeFromVersion]. If the buffer moved since
     * (typing while the disk was re-read), nothing is applied and the
     * answer is [ResolveOutcome.BufferChanged]: review again (DS-7).
     * A hand-settled merge may drop lines of mine, so it is refused
     * ([ResolveOutcome.NotKept]) when History could not keep the buffer,
     * unless [evenIfNotKept].
     */
    suspend fun useText(
        uri: DocumentUri,
        text: String,
        madeFromVersion: Long,
        evenIfNotKept: Boolean = false,
    ): ResolveOutcome = resolving(uri) { changes.useText(it, madeFromVersion, requireKept = !evenIfNotKept) { text } }

    /**
     * "Merge my edits" with no overlaps: merged from the buffer as it is
     * when the resolution applies, so typing after the tap is merged too.
     * [ResolveOutcome.BufferChanged] if that typing made an overlap.
     */
    suspend fun useCleanMerge(uri: DocumentUri): ResolveOutcome =
        resolving(uri) { entry ->
            // A clean merge keeps every change of mine, so it does not depend on History.
            changes.useText(entry, madeFromVersion = null, requireKept = false) { conflict ->
                conflict.merge(entry.document.snapshot())?.cleanText()
            }
        }

    /** "Use my version…": the buffer as it is when the resolution applies, every keystroke included. */
    suspend fun useMine(uri: DocumentUri): ResolveOutcome =
        resolving(uri) { entry ->
            changes.useText(entry, madeFromVersion = null, requireKept = false) { entry.document.snapshot().toString() }
        }

    /**
     * "Discard edits" on the recovery banner: History keeps them, the file
     * comes back clean. [ResolveOutcome.NotKept], with nothing changed,
     * when History could not keep them, unless [evenIfNotKept] (R2-DS-1).
     */
    suspend fun discardEdits(
        uri: DocumentUri,
        evenIfNotKept: Boolean = false,
    ): ResolveOutcome = resolving(uri) { changes.discard(it, evenIfNotKept) }

    /** Syncs every journal: `onPause` / `onStop`. Queues the work; see [awaitJournals]. */
    fun flushJournals() = context.entries.values.forEach(OpenDocument::flushJournal)

    /**
     * Blocks for at most [timeoutMillis] until every queued journal write,
     * including [flushJournals]'s, is on disk. For `onPause`/`onStop` only,
     * where the process may be killed next. Returns false on timeout.
     */
    fun awaitJournals(timeoutMillis: Long): Boolean = context.journalThread.drain(timeoutMillis)

    /**
     * "Discard them" for edits from an earlier session that could not be
     * applied ([Recovery.Kept]): they are moved into the journal's
     * quarantine, never deleted outright.
     */
    fun discardHeldEdits(uri: DocumentUri) {
        context.entries[uri]?.journal?.discardHeld()
    }

    /**
     * Edits that cannot reach their file, rebuilt into [draft] (a new,
     * empty draft the caller made) and saved there; then [uri]'s journal
     * is set aside, never deleted (R2-DS-2, R2-DS-3). For a document whose
     * open failed with [OpenOutcome.Failed.hasUnsavedEdits] after a
     * restart, or one open with [Recovery.Kept] edits. The draft joins the
     * store like any opened document.
     */
    suspend fun recoverAsCopy(
        uri: DocumentUri,
        draft: DocumentUri,
    ): CopyOutcome = copies.copy(uri, draft)

    /** Edits set aside into quarantine, newest first: the Storage screen's list (R2-DS-3). */
    suspend fun setAsideEdits(): List<SetAsideEdits> = copies.setAsideEdits()

    /**
     * Exports [edits] (their rebuilt text and raw journals) into [zip] for
     * the user to keep; true only when every journal is in it, byte for
     * byte. The Storage screen's clear runs this first unless the user
     * unticked "Export first".
     */
    suspend fun exportSetAsideEdits(
        edits: List<SetAsideEdits>,
        zip: File,
    ): Boolean = copies.exportSetAside(edits, zip)

    /**
     * Deletes [edits] from quarantine, and only those: the user confirmed
     * it on the Storage screen ("This can't be undone"). Never called by
     * the app on its own; see [SET_ASIDE_CAP_BYTES]. Returns how many went.
     */
    suspend fun clearSetAsideEdits(edits: List<SetAsideEdits>): Int = copies.deleteSetAside(edits)

    /** [edits]' text, rebuilt into [draft]; the set-aside journal stays where it is. */
    suspend fun recoverSetAsideAsCopy(
        edits: SetAsideEdits,
        draft: DocumentUri,
    ): CopyOutcome = copies.copySetAside(edits, draft)

    /**
     * A window lets go of [uri]. The entry stays while another window
     * shows it, and a dirty entry stays regardless unless [discardUnsaved]
     * (the "Don't save" answer, which records a History checkpoint first).
     *
     * "Don't save" deletes the journal only once History holds the text;
     * if it could not ([CheckpointResult.Off] or [CheckpointResult.Failed])
     * the answer is [CloseOutcome.NotKept], the entry stays open in its
     * window and nothing is deleted, unless [evenIfNotKept] (R2-DS-1).
     * Runs under the entry's lock, after any save or check in progress, so
     * a save that lands afterwards can never start a journal for a
     * document the user chose not to keep (R2-DS-6).
     */
    suspend fun close(
        uri: DocumentUri,
        discardUnsaved: Boolean = false,
        evenIfNotKept: Boolean = false,
    ): CloseOutcome {
        val entry = context.entries[uri] ?: return CloseOutcome.NotOpen
        return entry.lock.withLock { closeLocked(entry, discardUnsaved, evenIfNotKept) }
    }

    @Suppress("ReturnCount") // Moved, still shown, kept unsaved, still moving and not kept: five reasons to stay.
    private suspend fun closeLocked(
        entry: OpenDocument,
        discardUnsaved: Boolean,
        evenIfNotKept: Boolean,
    ): CloseOutcome {
        val uri = entry.uri
        if (context.entries[uri] !== entry) return CloseOutcome.NotOpen // Closed or moved while we waited.
        entry.windows = (entry.windows - 1).coerceAtLeast(0)
        if (entry.windows > 0) return CloseOutcome.StillShown
        if (entry.isDirty && !discardUnsaved) return CloseOutcome.KeptUnsaved
        if (entry.isDirty) {
            // Durable before the journal goes: it may be the only other copy (DS-12). Typing that never
            // stops is in no checkpoint, so the entry stays in memory, journalled (R2-DS-5).
            val kept =
                context.checkpointSettled(entry, CheckpointReason.ClosedWithoutSaving)
                    ?: return CloseOutcome.KeptUnsaved
            if (kept != CheckpointResult.Recorded && !evenIfNotKept) {
                entry.windows++ // Still on screen: the user is asked, not left.
                return CloseOutcome.NotKept(kept)
            }
        }
        context.entries.remove(uri)
        // A held journal (edits an earlier session could not apply) is never ours to end: close keeps it.
        if (entry.journal.isHolding) entry.journal.close() else entry.journal.endClean()
        context.deleteBaseCopyAfterJournal(uri)
        return CloseOutcome.Closed
    }

    /**
     * The memory governor's "inactive clean buffers" step: drops clean
     * entries no window shows; they reload from disk on demand. Dirty
     * buffers are never evicted, and neither is an entry a save or check
     * is working on (its lock is held), whose base copy that step may be
     * about to commit (R2-DS-6). Returns how many went.
     */
    suspend fun evictInactiveClean(): Int {
        val idle =
            context.entries.values.filter {
                it.windows == 0 && !it.isDirty && it.conflict == null && it.lock.tryLock()
            }
        try {
            idle.forEach {
                context.entries.remove(it.uri)
                it.journal.endClean() // Its undo history goes with it: it reloads from disk, history and all gone.
            }
            withContext(context.io) { idle.forEach { context.baseCopies.delete(it.uri) } }
        } finally {
            idle.forEach { it.lock.unlock() }
        }
        return idle.size
    }

    private suspend fun resolving(
        uri: DocumentUri,
        block: suspend (OpenDocument) -> ResolveOutcome,
    ): ResolveOutcome {
        val entry = context.entries[uri] ?: return ResolveOutcome.NoConflict
        return entry.lock.withLock { block(entry) }
    }
}

/**
 * The launch scan's findings.
 *
 * @property offers documents with recoverable edits.
 * @property unsupported journals from a newer app version, kept untouched.
 * @property quarantined unparseable files moved aside this time.
 * @property failures files that could not be read or moved.
 * @property repairedSaves files a save was torn in the middle of, put back to their last saved bytes.
 */
data class RecoveryScan(
    val offers: List<RecoveryOffer>,
    val unsupported: Int,
    val quarantined: Int,
    val failures: Int,
    val repairedSaves: Int = 0,
)

/** What [DocumentStore.close] did. */
sealed interface CloseOutcome {
    /** Gone from memory; the journal and base copy are retired. */
    data object Closed : CloseOutcome

    /** Another window still shows it. */
    data object StillShown : CloseOutcome

    /** It has unsaved edits, so it stays in memory, journalled. Ask Save / Don't save / Keep editing. */
    data object KeptUnsaved : CloseOutcome

    /**
     * "Don't save", but History could not keep a copy, so nothing was
     * closed or deleted: the document is still open, dirty and
     * journalled. Ask "Discard anyway?" (R2-DS-1).
     *
     * @property checkpoint what History answered: [CheckpointResult.Off] or [CheckpointResult.Failed].
     */
    data class NotKept(
        val checkpoint: CheckpointResult,
    ) : CloseOutcome

    /** It was not open. */
    data object NotOpen : CloseOutcome
}
