package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.save.FileIntegrity
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.files.save.SaveRequest
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.Snapshot
import kotlinx.coroutines.withContext

/**
 * Runs the [com.scorchsoft.ssmarkdown.files.save.SavePipeline] for an
 * open document and applies its outcome: only a verified save marks the
 * document clean, records History and ends the journal. Callers hold the
 * entry's lock.
 */
internal class SaveCoordinator(
    private val context: StoreContext,
    private val changes: OutsideChanges,
) {
    suspend fun save(entry: OpenDocument): SaveOutcome {
        val blocked = blockedOutcome(entry) ?: return finish(entry, write(entry, entry.uri, entry.base))
        return finish(entry, blocked)
    }

    /**
     * Writes the buffer to [target], a document the user just picked or
     * created ("Save as…"); on success the entry moves there, takes the
     * target's name, and the old URI's journal and base copy are retired.
     *
     * A failure is about the target, never the source: it is reported,
     * but the source is not marked missing or in conflict because of it.
     *
     * A target that is already open here is never overwritten while it
     * has unsaved edits, a conflict, held edits or a window
     * ([SaveOutcome.TargetOpen], R2-DS-4); a clean one nobody shows is
     * simply replaced. The caller holds the store's open lock, so no
     * window can open the target while the write runs.
     */
    suspend fun saveAs(
        entry: OpenDocument,
        target: DocumentUri,
    ): SaveOutcome {
        val occupant = context.entries[target]?.takeIf { it !== entry }
        if (occupant != null && !occupant.replaceable) return finish(entry, SaveOutcome.TargetOpen, aboutSource = false)
        val picked =
            when (val current = withContext(context.io) { targetOf(target) }) {
                is ProviderResult.Ok -> current.value
                is ProviderResult.Failure -> return finish(entry, failureOutcome(current), aboutSource = false)
            }
        val from = entry.uri
        val outcome = write(entry, target, picked.base, ignoreReadOnly = true)
        if (outcome is SaveOutcome.Saved) {
            moveTo(entry, from, target, outcome.fingerprint, picked.displayName)
            settleAfterJournal(target, outcome.fingerprint)
            context.deleteBaseCopyAfterJournal(from)
            context.notify(DocumentEvent.Moved(target, from))
        }
        return finish(entry, outcome, aboutSource = false)
    }

    /**
     * Save as succeeded: the entry now lives at [target]. Runs in one step
     * on the owner thread, so no keystroke can fall between the old
     * journal and the new one. Edits typed during the write are already in
     * the buffer (it is dirty); the target's journal starts from a snapshot
     * of them *before* the old journal is retired, so at every moment one
     * journal holds them (DS-6).
     */
    private fun moveTo(
        entry: OpenDocument,
        from: DocumentUri,
        target: DocumentUri,
        saved: BaseFingerprint,
        targetName: String?,
    ) {
        val old = entry.journal
        val fresh = context.journalFor(target)
        entry.journal = fresh // Before it starts, so its run carries this document's undo history.
        when {
            entry.isDirty -> fresh.resume(saved, entry.document.snapshot(), replacing = null)
            entry.hasHistory -> fresh.resumeClean(saved, replacing = null)
            else -> Unit
        }
        old.endClean()
        context.entries.remove(from)
        // Checked before the write: clean, no window, nothing held. It reloads from disk if opened again.
        context.entries[target]?.takeIf { it !== entry }?.let { context.entries.remove(target) }
        entry.uri = target
        // A draft's own name is its file id: never show it once the text has a real home.
        entry.displayName = targetName
        entry.readOnly = null
        entry.conflict = null
        context.entries[target] = entry
    }

    /** An entry Save as may replace: nothing in it exists anywhere else, and no window shows it. */
    private val OpenDocument.replaceable: Boolean
        get() = windows == 0 && !isDirty && conflict == null && !holdsUnappliedEdits

    @Suppress("ReturnCount") // A precedence list: conflict, then read-only, then nothing to do.
    private fun blockedOutcome(entry: OpenDocument): SaveOutcome? {
        entry.conflict?.let { return SaveOutcome.Conflict(it.theirsFingerprint) }
        if (entry.readOnly != null) return SaveOutcome.ReadOnly
        if (!entry.isDirty && !entry.fileMissing) return SaveOutcome.Saved(entry.base)
        return null
    }

    private suspend fun write(
        entry: OpenDocument,
        target: DocumentUri,
        base: BaseFingerprint,
        ignoreReadOnly: Boolean = false,
    ): SaveOutcome {
        entry.flushJournal()
        // The journal is durable before the file is touched, and the last save's settling has run, so
        // the base copy is the version this write replaces (robustness §1: flush before any risky step).
        context.journalThread.call { }
        val document = entry.document
        val version = document.version
        val snapshot = document.snapshot()
        val request =
            SaveRequest(
                target,
                snapshot,
                document.format,
                base,
                readOnly =
                    !ignoreReadOnly && entry.readOnly != null,
            )
        val outcome = withContext(context.io) { context.pipeline.save(request) }
        if (outcome is SaveOutcome.Saved) applySaved(entry, target, snapshot, version, outcome)
        return outcome
    }

    /**
     * A verified save: the saved version is clean, and the journal starts
     * again from what was typed during the write, or carries on as a clean
     * journal on the saved file with one Rebase record (ADR 0016: undo
     * survives a save and a later death), or ends when there is no history
     * the journal would keep (none, or only steps over its 8 MB cap: r3 M2).
     *
     * Not a suspend function, on purpose. It runs in one step on the owner
     * thread, right after the write returns, so the version check and the
     * journal decision see the same text: a keystroke either happened
     * before it (and is in the new journal's snapshot) or happens after it
     * (and is journalled on the new base). There is no window between
     * (DS-1, DS-2). The journal's file work is queued, in that order.
     */
    private fun applySaved(
        entry: OpenDocument,
        target: DocumentUri,
        snapshot: Snapshot,
        version: Long,
        saved: SaveOutcome.Saved,
    ) {
        val typedDuringSave = entry.document.version != version
        entry.markClean(saved.fingerprint, version)
        context.history.onSaved(target, snapshot, saved.fingerprint)
        if (target != entry.uri) return // Save as: moveTo hands the journal over.
        when {
            typedDuringSave -> entry.journal.resume(saved.fingerprint, entry.document.snapshot())
            entry.hasHistory -> entry.journal.saved(saved.fingerprint)
            else -> entry.journal.endClean()
        }
        settleAfterJournal(target, saved.fingerprint)
    }

    /**
     * Queued after the journal step, on the journal thread: only once the
     * journal has moved to the saved version may the base copy become it.
     * Until then a kill leaves the old bytes beside the old journal, and
     * the launch scan can still apply one to the other (robustness §2).
     */
    private fun settleAfterJournal(
        target: DocumentUri,
        saved: BaseFingerprint,
    ) {
        context.journalThread.execute { context.pipeline.finish(target, saved) }
    }

    /** [aboutSource] is false for Save as, whose conflicts and missing files are the target's. */
    private suspend fun finish(
        entry: OpenDocument,
        outcome: SaveOutcome,
        aboutSource: Boolean = true,
    ): SaveOutcome {
        entry.lastSaveOutcome = outcome
        when (outcome.takeIf { aboutSource }) {
            is SaveOutcome.Conflict -> {
                if (entry.conflict == null) changes.diskChanged(entry)
            }

            is SaveOutcome.Missing -> {
                if (!entry.fileMissing) context.notify(DocumentEvent.FileMissing(entry.uri))
                entry.fileMissing = true
            }

            else -> {
                Unit
            }
        }
        context.notify(DocumentEvent.SaveFinished(entry.uri, outcome))
        return outcome
    }

    /** What Save as needs of the picked document before writing: its fingerprint and its name. */
    private class PickedTarget(
        val base: BaseFingerprint,
        val displayName: String?,
    )

    private fun targetOf(uri: DocumentUri): ProviderResult<PickedTarget> {
        val metadata =
            when (val result = context.provider.metadata(uri)) {
                is ProviderResult.Ok -> result.value
                is ProviderResult.Failure -> return result
            }
        return when (val digest = context.probe.digest(uri)) {
            is ProviderResult.Ok -> {
                ProviderResult.Ok(
                    PickedTarget(
                        BaseFingerprint(digest.value.size, metadata.lastModified, digest.value.sha256),
                        metadata.displayName,
                    ),
                )
            }

            is ProviderResult.Failure -> {
                digest
            }
        }
    }

    private fun failureOutcome(failure: ProviderResult.Failure): SaveOutcome =
        when (failure) {
            ProviderResult.NotFound -> SaveOutcome.Missing(FileIntegrity.Untouched)
            ProviderResult.PermissionDenied -> SaveOutcome.PermissionLost(FileIntegrity.Untouched)
            ProviderResult.StorageFull -> SaveOutcome.StorageFull(FileIntegrity.Untouched)
            else -> SaveOutcome.ProviderError(failure, FileIntegrity.Untouched)
        }
}
