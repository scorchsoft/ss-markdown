package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fingerprint.DiskState
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The disk is always the truth (robustness §2a): checks for outside
 * changes, the silent reload of a clean buffer, conflict detection for a
 * dirty one, and the conflict sheet's choices.
 *
 * Callers hold the entry's lock.
 */
internal class OutsideChanges(
    private val context: StoreContext,
) {
    suspend fun check(
        entry: OpenDocument,
        reason: CheckReason,
    ): OutsideChange {
        if (entry.conflict != null) return OutsideChange.Conflicted
        val state = withContext(context.io) { context.probe.check(entry.uri, entry.base, reason.thorough) }
        return when (state) {
            is DiskState.Unchanged -> {
                entry.base = state.fingerprint // Same bytes; refresh lastModified for the cheap check.
                entry.fileMissing = false
                OutsideChange.None
            }

            is DiskState.Changed -> {
                diskChanged(entry)
            }

            DiskState.Missing -> {
                if (!entry.fileMissing) context.notify(DocumentEvent.FileMissing(entry.uri))
                entry.fileMissing = true
                OutsideChange.Missing
            }

            DiskState.PermissionLost -> {
                OutsideChange.PermissionLost
            }

            is DiskState.Unreadable -> {
                OutsideChange.Unavailable(state.failure)
            }
        }
    }

    /**
     * Loads the new disk version once, then decides: a clean buffer is
     * replaced silently; a dirty one (including one that became dirty
     * while the load ran) gets a pending conflict and is never touched.
     *
     * The dirty check and the swap happen in one step on the owner thread,
     * after the last suspension, so a keystroke typed while the disk was
     * read turns the reload into a conflict instead of being thrown away
     * (DS-4). The base copy is committed after the swap: until it lands,
     * a journal started on the new base still replays onto the disk.
     */
    @Suppress("ReturnCount") // Unreadable, dirty and clean each end differently.
    suspend fun diskChanged(entry: OpenDocument): OutsideChange {
        val staged = context.baseCopies.stage(entry.uri)
        val loaded = withContext(context.io) { context.loader.load(entry.uri, staged) }
        if (loaded !is LoadResult.Loaded) {
            staged.abandon()
            return unavailable(loaded)
        }
        if (entry.isDirty) {
            staged.abandon() // The old copy is the merge base.
            // The copies are built on io, not the main thread: 40 MB of chars each for a 20 MB file (PERF-22).
            val (base, theirs) =
                withContext(context.io) {
                    context.readBaseText(entry.uri, entry.base)?.text?.toString() to loaded.document.text.toString()
                }
            entry.conflict = ConflictState(base, theirs, loaded.fingerprint, recovered = false)
            context.notify(DocumentEvent.ConflictDetected(entry.uri))
            return OutsideChange.Conflicted
        }
        context.history.onChangedOutside(entry.uri, loaded.document.snapshot(), loaded.fingerprint)
        entry.journal.endClean() // A clean buffer runs no journal; never let one outlive its base.
        swapIn(entry, loaded, dirty = false)
        entry.readOnly = loaded.readOnly
        withContext(NonCancellable + context.io) { staged.commit() }
        return OutsideChange.Reloaded
    }

    /** "Keep the file as it is": mine goes to History, the disk version becomes the clean buffer. */
    suspend fun keepTheirs(entry: OpenDocument): ResolveOutcome =
        resolve(entry) { loaded, _ ->
            checkpointSettled(entry, CheckpointReason.KeptTheirs)
            entry.journal.endClean()
            swapIn(entry, loaded, dirty = false)
            ResolveOutcome.Resolved
        }

    /**
     * "Merge my edits" or "Use my version": [textOf] gives the new text,
     * an unsaved edit on top of the disk version, which is recorded in
     * History first and becomes the new base. Nothing is written until the
     * user saves.
     *
     * The buffer as it was is checkpointed first (DS-8), and the text is
     * taken in the same step as the swap, after the last suspension, so
     * nothing typed after the tap is lost (DS-7). [madeFromVersion], when
     * given, is the buffer version [textOf]'s text was made from; if the
     * buffer moved since, nothing is applied.
     */
    suspend fun useText(
        entry: OpenDocument,
        madeFromVersion: Long?,
        textOf: (ConflictState) -> String?,
    ): ResolveOutcome =
        resolve(entry) { loaded, conflict ->
            checkpointSettled(entry, CheckpointReason.BeforeMerge)
            val moved = madeFromVersion != null && entry.document.version != madeFromVersion
            val text = if (moved) null else textOf(conflict)
            if (text == null) return@resolve ResolveOutcome.BufferChanged
            context.history.onChangedOutside(entry.uri, loaded.document.snapshot(), loaded.fingerprint)
            loaded.document.replaceAllWith(text)
            swapIn(entry, loaded, dirty = true)
            entry.journal.resume(loaded.fingerprint, entry.document.snapshot())
            ResolveOutcome.Resolved
        }

    /** "Discard edits" on recovered work: checkpoint, then the disk version, clean. */
    @Suppress("ReturnCount") // Conflict, unreadable disk and success each end differently.
    suspend fun discard(entry: OpenDocument): ResolveOutcome {
        if (entry.conflict != null) return keepTheirsAfterDiscard(entry)
        val staged = context.baseCopies.stage(entry.uri)
        val loaded = withContext(context.io) { context.loader.load(entry.uri, staged) }
        if (loaded !is LoadResult.Loaded) {
            staged.abandon()
            return ResolveOutcome.Unavailable(unavailable(loaded))
        }
        checkpointSettled(entry, CheckpointReason.DiscardedEdits)
        entry.journal.endClean()
        swapIn(entry, loaded, dirty = false)
        withContext(NonCancellable + context.io) { staged.commit() }
        return ResolveOutcome.Resolved
    }

    private suspend fun keepTheirsAfterDiscard(entry: OpenDocument): ResolveOutcome {
        context.history.onCheckpoint(entry.uri, entry.document.snapshot(), CheckpointReason.DiscardedEdits)
        return keepTheirs(entry)
    }

    /**
     * Records the buffer in History and waits until it is durable, again if
     * typing moved it meanwhile, so the version kept is the one about to be
     * replaced. The caller replaces it in the same step as this returns.
     */
    private suspend fun checkpointSettled(
        entry: OpenDocument,
        reason: CheckpointReason,
    ): CheckpointResult {
        var attempts = 0
        while (true) {
            val version = entry.document.version
            val result = context.history.onCheckpoint(entry.uri, entry.document.snapshot(), reason)
            if (entry.document.version == version || ++attempts >= MAX_CHECKPOINT_ATTEMPTS) return result
        }
    }

    /**
     * Re-reads the disk; resolves only if it is still the version the user
     * looked at, and only if [apply] says so. The base copy moves to the
     * disk version only once the resolution has been applied.
     */
    @Suppress("ReturnCount") // No conflict, unreadable, changed again and applied are the four answers.
    private suspend fun resolve(
        entry: OpenDocument,
        apply: suspend (LoadResult.Loaded, ConflictState) -> ResolveOutcome,
    ): ResolveOutcome {
        val conflict = entry.conflict ?: return ResolveOutcome.NoConflict
        val staged = context.baseCopies.stage(entry.uri)
        val loaded = withContext(context.io) { context.loader.load(entry.uri, staged) }
        if (loaded !is LoadResult.Loaded) {
            staged.abandon()
            return ResolveOutcome.Unavailable(unavailable(loaded))
        }
        if (!loaded.fingerprint.matches(conflict.theirsFingerprint)) {
            staged.abandon()
            val theirs = withContext(context.io) { loaded.document.text.toString() }
            entry.conflict = ConflictState(conflict.base, theirs, loaded.fingerprint, conflict.recovered)
            context.notify(DocumentEvent.ConflictDetected(entry.uri))
            return ResolveOutcome.ChangedAgain
        }
        val outcome = apply(loaded, conflict)
        if (outcome != ResolveOutcome.Resolved) {
            staged.abandon()
            return outcome
        }
        entry.conflict = null
        if (entry.readOnly == ReadOnlyReason.NewerRecoveryData) entry.readOnly = null
        withContext(NonCancellable + context.io) { staged.commit() }
        return outcome
    }

    private fun swapIn(
        entry: OpenDocument,
        loaded: LoadResult.Loaded,
        dirty: Boolean,
    ) {
        val previous = entry.document
        val oldText = previous.snapshot()
        val newText = loaded.document.snapshot()
        entry.replaceDocument(loaded.document, loaded.fingerprint, dirty)
        context.notify(DocumentEvent.Reloaded(entry.uri, previous) { context.cursorMapper.map(oldText, newText, it) })
    }

    private fun unavailable(result: LoadResult): OutsideChange =
        when (result) {
            is LoadResult.Failed -> {
                when (result.failure) {
                    ProviderResult.NotFound -> OutsideChange.Missing
                    ProviderResult.PermissionDenied -> OutsideChange.PermissionLost
                    else -> OutsideChange.Unavailable(result.failure)
                }
            }

            else -> {
                OutsideChange.Unavailable(ProviderResult.Failed(IllegalStateException("cannot load: $result")))
            }
        }

    private companion object {
        /** Continuous typing could move the buffer forever; after this many tries the newest checkpoint stands. */
        const val MAX_CHECKPOINT_ATTEMPTS = 3
    }
}
