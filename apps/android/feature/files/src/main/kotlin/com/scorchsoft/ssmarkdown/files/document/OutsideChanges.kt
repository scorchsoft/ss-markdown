package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fingerprint.DiskState
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
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
            val base = withContext(context.io) { context.readBaseText(entry.uri, entry.base) }
            entry.conflict =
                ConflictState(
                    base?.text?.toString(),
                    loaded.document.text.toString(),
                    loaded.fingerprint,
                    recovered = false,
                )
            context.notify(DocumentEvent.ConflictDetected(entry.uri))
            return OutsideChange.Conflicted
        }
        withContext(context.io) { staged.commit() }
        context.history.onChangedOutside(entry.uri, loaded.document.snapshot(), loaded.fingerprint)
        swapIn(entry, loaded, dirty = false)
        entry.readOnly = loaded.readOnly
        return OutsideChange.Reloaded
    }

    /** "Keep the file as it is": mine goes to History, the disk version becomes the clean buffer. */
    suspend fun keepTheirs(entry: OpenDocument): ResolveOutcome =
        resolve(entry) { loaded ->
            context.history.onCheckpoint(entry.uri, entry.document.snapshot(), CheckpointReason.KeptTheirs)
            withContext(context.io) { entry.journal.endClean() }
            swapIn(entry, loaded, dirty = false)
        }

    /**
     * "Merge my edits" or "Use my version": [text] becomes an unsaved edit
     * on top of the disk version, which is recorded in History first and
     * becomes the new base. Nothing is written until the user saves.
     */
    suspend fun useText(
        entry: OpenDocument,
        text: String,
    ): ResolveOutcome =
        resolve(entry) { loaded ->
            context.history.onChangedOutside(entry.uri, loaded.document.snapshot(), loaded.fingerprint)
            loaded.document.replaceAllWith(text)
            swapIn(entry, loaded, dirty = true)
            withContext(context.io) { entry.journal.restartOn(loaded.fingerprint, text) }
        }

    /** "Discard edits" on recovered work: checkpoint, then the disk version, clean. */
    @Suppress("ReturnCount") // Conflict, unreadable disk and success each end differently.
    suspend fun discard(entry: OpenDocument): ResolveOutcome {
        if (entry.conflict != null) return keepTheirsAfterDiscard(entry)
        context.history.onCheckpoint(entry.uri, entry.document.snapshot(), CheckpointReason.DiscardedEdits)
        val staged = context.baseCopies.stage(entry.uri)
        val loaded = withContext(context.io) { context.loader.load(entry.uri, staged) }
        if (loaded !is LoadResult.Loaded) {
            staged.abandon()
            return ResolveOutcome.Unavailable(unavailable(loaded))
        }
        withContext(context.io) {
            staged.commit()
            entry.journal.endClean()
        }
        swapIn(entry, loaded, dirty = false)
        return ResolveOutcome.Resolved
    }

    private suspend fun keepTheirsAfterDiscard(entry: OpenDocument): ResolveOutcome {
        context.history.onCheckpoint(entry.uri, entry.document.snapshot(), CheckpointReason.DiscardedEdits)
        return keepTheirs(entry)
    }

    /** Re-reads the disk; resolves only if it is still the version the user looked at. */
    @Suppress("ReturnCount") // No conflict, unreadable, changed again and resolved are the four answers.
    private suspend fun resolve(
        entry: OpenDocument,
        apply: suspend (LoadResult.Loaded) -> Unit,
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
            entry.conflict =
                ConflictState(conflict.base, loaded.document.text.toString(), loaded.fingerprint, conflict.recovered)
            context.notify(DocumentEvent.ConflictDetected(entry.uri))
            return ResolveOutcome.ChangedAgain
        }
        withContext(context.io) { staged.commit() }
        entry.conflict = null
        apply(loaded)
        if (entry.readOnly == ReadOnlyReason.NewerRecoveryData) entry.readOnly = null
        return ResolveOutcome.Resolved
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
}
