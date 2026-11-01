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
     * created ("Save as…"); on success the entry moves there and the old
     * URI's journal and base copy are retired.
     */
    suspend fun saveAs(
        entry: OpenDocument,
        target: DocumentUri,
    ): SaveOutcome {
        val targetBase =
            when (val current = withContext(context.io) { fingerprintOf(target) }) {
                is ProviderResult.Ok -> current.value
                is ProviderResult.Failure -> return finish(entry, failureOutcome(current))
            }
        val from = entry.uri
        val outcome = write(entry, target, targetBase, ignoreReadOnly = true)
        if (outcome is SaveOutcome.Saved) {
            withContext(context.io) {
                entry.journal.endClean()
                context.baseCopies.delete(from)
            }
            context.entries.remove(from)
            entry.uri = target
            entry.journal = context.journalFor(target)
            entry.readOnly = null
            entry.conflict = null
            context.entries[target] = entry
            context.notify(DocumentEvent.Moved(target, from))
        }
        return finish(entry, outcome)
    }

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

    private suspend fun applySaved(
        entry: OpenDocument,
        target: DocumentUri,
        snapshot: Snapshot,
        version: Long,
        saved: SaveOutcome.Saved,
    ) {
        val typedDuringSave = entry.document.version != version
        entry.markClean(saved.fingerprint, version)
        context.history.onSaved(target, snapshot, saved.fingerprint)
        if (target != entry.uri) return // Save as: the caller retires the old journal and starts afresh.
        val text = if (typedDuringSave) entry.document.text.toString() else null
        withContext(context.io) {
            if (text == null) entry.journal.endClean() else entry.journal.restartOn(saved.fingerprint, text)
        }
    }

    private suspend fun finish(
        entry: OpenDocument,
        outcome: SaveOutcome,
    ): SaveOutcome {
        entry.lastSaveOutcome = outcome
        when (outcome) {
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

    private fun fingerprintOf(uri: DocumentUri): ProviderResult<BaseFingerprint> {
        val metadata =
            when (val result = context.provider.metadata(uri)) {
                is ProviderResult.Ok -> result.value
                is ProviderResult.Failure -> return result
            }
        return when (val digest = context.probe.digest(uri)) {
            is ProviderResult.Ok -> {
                ProviderResult.Ok(
                    BaseFingerprint(digest.value.size, metadata.lastModified, digest.value.sha256),
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
