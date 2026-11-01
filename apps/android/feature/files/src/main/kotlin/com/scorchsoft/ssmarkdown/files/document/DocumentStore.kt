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
     * session. Opening one restores them. Base copies that no journal
     * needs are cleared on the way.
     */
    suspend fun scanForRecovery(): RecoveryScan =
        withContext(context.io) {
            val scan = context.journals.scan()
            val offers = scan.unclean.map { RecoveryOffer(DocumentUri(it.documentKey), it.createdAtMillis, it.base) }
            offers.forEach { context.journalTimes[it.uri] = it.editedAtMillis }
            context.baseCopies.retainOnly(offers.map { it.uri } + context.entries.keys)
            RecoveryScan(offers, scan.unsupported.size, scan.quarantined.size, scan.failures.size)
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

    /** Save as: writes [uri]'s buffer to [target] and moves the entry there. */
    suspend fun saveAs(
        uri: DocumentUri,
        target: DocumentUri,
    ): SaveOutcome {
        val entry = context.entries[uri] ?: return SaveOutcome.Missing(FileIntegrity.Untouched)
        return entry.lock.withLock { withContext(NonCancellable) { saves.saveAs(entry, target) } }
    }

    /** Looks for an outside change now: on resume, on a timer, on a `ContentObserver` ping. */
    suspend fun checkOutside(
        uri: DocumentUri,
        reason: CheckReason,
    ): OutsideChange {
        val entry = context.entries[uri] ?: return OutsideChange.NotOpen
        return entry.lock.withLock { changes.check(entry, reason) }
    }

    /** "Keep the file as it is". */
    suspend fun keepTheirs(uri: DocumentUri): ResolveOutcome = resolving(uri) { changes.keepTheirs(it) }

    /**
     * "Merge my edits" with a merge the user settled by hand: [text] was
     * made from the buffer at [madeFromVersion]. If the buffer moved since
     * (typing while the disk was re-read), nothing is applied and the
     * answer is [ResolveOutcome.BufferChanged]: review again (DS-7).
     */
    suspend fun useText(
        uri: DocumentUri,
        text: String,
        madeFromVersion: Long,
    ): ResolveOutcome = resolving(uri) { changes.useText(it, madeFromVersion) { text } }

    /**
     * "Merge my edits" with no overlaps: merged from the buffer as it is
     * when the resolution applies, so typing after the tap is merged too.
     * [ResolveOutcome.BufferChanged] if that typing made an overlap.
     */
    suspend fun useCleanMerge(uri: DocumentUri): ResolveOutcome =
        resolving(uri) { entry ->
            changes.useText(entry, madeFromVersion = null) { conflict ->
                conflict.merge(entry.document.snapshot())?.cleanText()
            }
        }

    /** "Use my version…": the buffer as it is when the resolution applies, every keystroke included. */
    suspend fun useMine(uri: DocumentUri): ResolveOutcome =
        resolving(
            uri,
        ) { entry -> changes.useText(entry, madeFromVersion = null) { entry.document.snapshot().toString() } }

    /** "Discard edits" on the recovery banner: History keeps them, the file comes back clean. */
    suspend fun discardEdits(uri: DocumentUri): ResolveOutcome = resolving(uri) { changes.discard(it) }

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
     * A window lets go of [uri]. The entry stays while another window
     * shows it, and a dirty entry stays regardless unless [discardUnsaved]
     * (the "Don't save" answer, which records a History checkpoint first).
     */
    @Suppress("ReturnCount") // Not open, still shown and kept-unsaved are the three reasons not to close.
    suspend fun close(
        uri: DocumentUri,
        discardUnsaved: Boolean = false,
    ): CloseOutcome {
        val entry = context.entries[uri] ?: return CloseOutcome.NotOpen
        entry.windows = (entry.windows - 1).coerceAtLeast(0)
        if (entry.windows > 0) return CloseOutcome.StillShown
        if (entry.isDirty && !discardUnsaved) return CloseOutcome.KeptUnsaved
        if (entry.isDirty) {
            // Durable before the journal goes: it may be the only other copy (DS-12).
            context.history.onCheckpoint(uri, entry.document.snapshot(), CheckpointReason.ClosedWithoutSaving)
        }
        context.entries.remove(uri)
        // A held journal (edits an earlier session could not apply) is never ours to end: close keeps it.
        if (entry.journal.isHolding) entry.journal.close() else entry.journal.endClean()
        withContext(context.io) { context.baseCopies.delete(uri) }
        return CloseOutcome.Closed
    }

    /**
     * The memory governor's "inactive clean buffers" step: drops clean
     * entries no window shows; they reload from disk on demand. Dirty
     * buffers are never evicted. Returns how many went.
     */
    suspend fun evictInactiveClean(): Int {
        val idle = context.entries.values.filter { it.windows == 0 && !it.isDirty && it.conflict == null }
        idle.forEach { context.entries.remove(it.uri) }
        withContext(context.io) { idle.forEach { context.baseCopies.delete(it.uri) } }
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
 */
data class RecoveryScan(
    val offers: List<RecoveryOffer>,
    val unsupported: Int,
    val quarantined: Int,
    val failures: Int,
)

/** What [DocumentStore.close] did. */
enum class CloseOutcome {
    /** Gone from memory; the journal and base copy are retired. */
    Closed,

    /** Another window still shows it. */
    StillShown,

    /** It has unsaved edits, so it stays in memory, journalled. Ask Save / Don't save / Keep editing. */
    KeptUnsaved,

    /** It was not open. */
    NotOpen,
}
