package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore.StagedCopy
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.history.journal.JournalHistory
import com.scorchsoft.ssmarkdown.history.journal.ReplayResult
import com.scorchsoft.ssmarkdown.history.journal.Salvage
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.text.UndoHistory
import kotlinx.coroutines.withContext

/**
 * Opening a document, including the recovery of an earlier session's
 * journal (robustness §1 and §2a). Never discards a journal with edits in
 * it: every path either applies it, resumes it, or leaves it exactly
 * where it is. A clean journal (undo history only, ADR 0016) restores the
 * history when the file is exactly its base, and is deleted when it is
 * not: an outside change ends the history.
 */
internal class DocumentOpener(
    private val context: StoreContext,
) {
    // Load failure and "no journal" end early; the rest is the replay dispatch.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    suspend fun open(uri: DocumentUri): OpenOutcome {
        context.unclaimedHistory.remove(uri)
        // Journal files are only ever touched on the journal thread, so this waits for any queued
        // work on this document (a close that is still ending its journal) before looking.
        val hasJournal = context.journalThread.call { context.journals.fileFor(uri.value).exists() }
        // Staging creates the base-copy directory: disk work, never on the caller's (main) thread.
        val staged = withContext(context.io) { context.baseCopies.stage(uri) }
        val loaded = withContext(context.io) { context.loader.load(uri, staged) }
        if (loaded !is LoadResult.Loaded) {
            staged.abandon()
            // A clean journal holds undo history only: nothing unsaved to rebuild as a copy (review r3 L1).
            val unsaved = hasJournal && !context.journalThread.call { context.journals.holdsHistoryOnly(uri.value) }
            return failed(loaded, unsaved)
        }
        if (!hasJournal) {
            withContext(context.io) { staged.commit() }
            val entry = register(uri, loaded)
            context.history.onOpened(uri, entry.document.snapshot(), loaded.fingerprint)
            return OpenOutcome.Opened(entry)
        }
        val replay =
            context.journalThread.call {
                context.journals.replay(uri.value, loaded.document.text.toString(), loaded.fingerprint)
            }
        return when (replay) {
            is ReplayResult.Ok -> {
                if (replay.editsReplayed) {
                    restored(uri, loaded, replay.text, replay.history, damaged = false, staged)
                } else {
                    reopenedClean(uri, loaded, replay.history, staged)
                }
            }

            is ReplayResult.Corrupt -> {
                restored(uri, loaded, replay.recoveredText, replay.history, damaged = true, staged)
            }

            is ReplayResult.BaseMismatch -> {
                if (context.journalThread.call { context.journals.holdsHistoryOnly(uri.value) }) {
                    // Undo history for bytes the file no longer holds: an outside change ends it.
                    context.journalThread.call { context.journals.markClean(uri.value, replay.journalBase) }
                    plain(uri, loaded, staged)
                } else {
                    conflicted(uri, loaded, replay, staged)
                }
            }

            ReplayResult.Missing -> {
                plain(uri, loaded, staged)
            }

            is ReplayResult.Unsupported -> {
                kept(uri, loaded, staged, "journal from a newer version", readOnly = true)
            }

            is ReplayResult.Unreadable -> {
                kept(
                    uri,
                    loaded,
                    staged,
                    "journal unreadable: ${replay.reason}",
                    readOnly = false,
                )
            }

            is ReplayResult.IoFailure -> {
                kept(uri, loaded, staged, "journal could not be read", readOnly = false)
            }
        }
    }

    /**
     * The disk is the journal's base: apply the edits and carry on
     * journalling, with the undo history the journal kept (ADR 0016).
     *
     * The text goes in as one replace and the history is restored as data,
     * rather than replaying every op into the piece tree: the replayed
     * text is already exact, the steps are just the ops that lead to it,
     * and one replace keeps a 20 MB recovery inside the open budget
     * (PERF-22). Restoring replaces the stacks, so that one replace is not
     * an undo step: Undo walks back through the steps the user made, never
     * "all the recovered edits at once".
     */
    @Suppress("LongParameterList") // The replay's three results plus the load it applies to.
    private suspend fun restored(
        uri: DocumentUri,
        loaded: LoadResult.Loaded,
        text: String,
        history: JournalHistory,
        damaged: Boolean,
        staged: StagedCopy,
    ): OpenOutcome {
        withContext(context.io) { staged.commit() }
        loaded.document.replaceAllWith(text)
        loaded.document.restoreOrForget(history)
        val entry = register(uri, loaded, dirty = true)
        entry.journal.adopt()
        entry.journal.resume(loaded.fingerprint, entry.document.snapshot(), replacing = loaded.fingerprint)
        return OpenOutcome.Opened(entry, Recovery.Restored(context.journalTimes[uri] ?: 0, damaged))
    }

    /**
     * The file changed since the journal began. Replay onto the base copy
     * to rebuild "mine", keep the old base as the document's base, and
     * leave the disk version as "theirs" in a pending conflict.
     */
    @Suppress("ReturnCount") // Each early return keeps the journal untouched for a reason named in it.
    private suspend fun conflicted(
        uri: DocumentUri,
        loaded: LoadResult.Loaded,
        mismatch: ReplayResult.BaseMismatch,
        staged: StagedCopy,
    ): OpenOutcome {
        staged.abandon() // The old base copy is the merge base; keep it.
        val journalBase = mismatch.journalBase
        val baseDocument =
            withContext(context.io) { context.readBaseText(uri, journalBase) }
                ?: return fromSnapshot(uri, loaded, journalBase)
                    ?: kept(uri, loaded, null, "file changed and no base copy to replay onto", readOnly = false)
        // Full-text copies are built off the main thread: 40 MB of chars for a 20 MB file (PERF-22).
        val baseText = withContext(context.io) { baseDocument.text.toString() }
        val mine =
            when (
                val replay =
                    context.journalThread.call {
                        context.journals.replay(
                            uri.value,
                            baseText,
                            journalBase,
                        )
                    }
            ) {
                is ReplayResult.Ok -> replay.text
                is ReplayResult.Corrupt -> replay.recoveredText
                else -> return kept(uri, loaded, null, "journal could not be replayed onto its base", readOnly = false)
            }
        baseDocument.replaceAllWith(mine)
        baseDocument.restoreOrForget(JournalHistory.EMPTY) // A conflict starts with no history (robustness §2a).
        val entry =
            register(
                uri,
                LoadResult.Loaded(baseDocument, journalBase, loaded.metadata, loaded.readOnly, loaded.budget),
                dirty = true,
            )
        val theirs = withContext(context.io) { loaded.document.text.toString() }
        entry.conflict = ConflictState(baseText, theirs, loaded.fingerprint, recovered = true)
        entry.journal.adopt()
        entry.journal.resume(journalBase, entry.document.snapshot(), replacing = journalBase)
        return OpenOutcome.Opened(entry, Recovery.Conflicted(context.journalTimes[uri] ?: 0))
    }

    /**
     * [conflicted] without a base copy: a journal that starts from a
     * snapshot (as every journal restarted after a save, a recovery or a
     * resolved conflict does) carries the whole text and needs no base.
     * Mine is that text and theirs the disk; with no base the merge is
     * unavailable, but nothing is held back. Null when the journal has no
     * snapshot to start from.
     */
    private suspend fun fromSnapshot(
        uri: DocumentUri,
        loaded: LoadResult.Loaded,
        journalBase: BaseFingerprint,
    ): OpenOutcome? {
        val salvage = context.journalThread.call { context.journals.salvage(uri.value) { null } }
        val mine = (salvage as? Salvage.Text)?.text ?: return null
        val theirs = withContext(context.io) { loaded.document.text.toString() }
        val document = loaded.document
        document.replaceAllWith(mine)
        document.restoreOrForget(JournalHistory.EMPTY)
        val entry =
            register(
                uri,
                LoadResult.Loaded(document, journalBase, loaded.metadata, loaded.readOnly, loaded.budget),
                dirty = true,
            )
        entry.conflict = ConflictState(base = null, theirs, loaded.fingerprint, recovered = true)
        entry.journal.adopt()
        entry.journal.resume(journalBase, entry.document.snapshot(), replacing = journalBase)
        return OpenOutcome.Opened(entry, Recovery.Conflicted(context.journalTimes[uri] ?: 0))
    }

    /**
     * A clean journal on exactly the file's bytes: the document opens
     * clean, as from disk, with the undo steps the journal kept, and the
     * journal carries on as this session's. With no history to restore (or
     * steps that do not fit the text), the journal is ended instead.
     */
    private suspend fun reopenedClean(
        uri: DocumentUri,
        loaded: LoadResult.Loaded,
        history: JournalHistory,
        staged: StagedCopy,
    ): OpenOutcome {
        val restoredHistory = !history.isEmpty && loaded.document.restoreHistory(history.toUndoHistory())
        if (!restoredHistory) {
            context.journalThread.call { context.journals.markClean(uri.value, loaded.fingerprint) }
            return plain(uri, loaded, staged)
        }
        val outcome = plain(uri, loaded, staged)
        val entry = (outcome as OpenOutcome.Opened).document
        entry.journal.adopt()
        entry.journal.resumeClean(loaded.fingerprint, replacing = loaded.fingerprint)
        return outcome
    }

    private suspend fun plain(
        uri: DocumentUri,
        loaded: LoadResult.Loaded,
        staged: StagedCopy,
    ): OpenOutcome {
        withContext(context.io) { staged.commit() }
        val entry = register(uri, loaded)
        context.history.onOpened(uri, entry.document.snapshot(), loaded.fingerprint)
        return OpenOutcome.Opened(entry)
    }

    /**
     * Opens the disk version and leaves the journal untouched, *held*: no
     * close, save or discard of this entry deletes it (DS-3). It stays
     * where the next launch finds it again until the user edits this
     * document, when it moves into quarantine, or chooses to discard it.
     */
    private suspend fun kept(
        uri: DocumentUri,
        loaded: LoadResult.Loaded,
        staged: StagedCopy?,
        reason: String,
        readOnly: Boolean,
    ): OpenOutcome {
        staged?.abandon()
        val entry = register(uri, loaded)
        entry.journal.hold()
        if (readOnly) entry.readOnly = ReadOnlyReason.NewerRecoveryData
        return OpenOutcome.Opened(entry, Recovery.Kept(reason))
    }

    private fun register(
        uri: DocumentUri,
        loaded: LoadResult.Loaded,
        dirty: Boolean = false,
    ): OpenDocument {
        val entry =
            OpenDocument(
                uri = uri,
                document = loaded.document,
                base = loaded.fingerprint,
                displayName = loaded.metadata.displayName,
                budget = loaded.budget,
                journal = context.journalFor(uri),
                notify = context::notify,
            )
        entry.readOnly = loaded.readOnly
        if (dirty) entry.replaceDocument(loaded.document, loaded.fingerprint, dirty = true)
        entry.windows = 1
        context.entries[uri] = entry
        return entry
    }

    private fun failed(
        result: LoadResult,
        hasUnsavedEdits: Boolean,
    ): OpenOutcome =
        when (result) {
            is LoadResult.Declined -> OpenOutcome.TooLarge(result.admission)
            is LoadResult.Failed -> OpenOutcome.Failed(result.failure, hasUnsavedEdits)
            is LoadResult.Loaded -> error("not a failure")
        }
}

/** Restores [history] into this document, or forgets every step if it does not fit the text. */
private fun TextDocument.restoreOrForget(history: JournalHistory) {
    if (!restoreHistory(history.toUndoHistory())) restoreHistory(UndoHistory.EMPTY)
}

private fun JournalHistory.toUndoHistory(): UndoHistory =
    UndoHistory(
        undo.map { step -> step.map { EditOp(it.offset, it.deleted, it.inserted) } },
        redo.map { step -> step.map { EditOp(it.offset, it.deleted, it.inserted) } },
    )
