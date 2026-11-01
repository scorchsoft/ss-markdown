package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore.StagedCopy
import com.scorchsoft.ssmarkdown.history.journal.ReplayResult
import kotlinx.coroutines.withContext

/**
 * Opening a document, including the recovery of an earlier session's
 * journal (robustness §1 and §2a). Never discards a journal: every path
 * either applies it, resumes it, or leaves it exactly where it is.
 */
internal class DocumentOpener(
    private val context: StoreContext,
) {
    @Suppress("ReturnCount") // Load failure and "no journal" end early; the rest is the replay dispatch.
    suspend fun open(uri: DocumentUri): OpenOutcome {
        // Journal files are only ever touched on the journal thread, so this waits for any queued
        // work on this document (a close that is still ending its journal) before looking.
        val hasJournal = context.journalThread.call { context.journals.fileFor(uri.value).exists() }
        val staged = context.baseCopies.stage(uri)
        val loaded = withContext(context.io) { context.loader.load(uri, staged) }
        if (loaded !is LoadResult.Loaded) {
            staged.abandon()
            return failed(loaded, hasJournal)
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
                restored(uri, loaded, replay.text, damaged = false, staged)
            }

            is ReplayResult.Corrupt -> {
                restored(uri, loaded, replay.recoveredText, damaged = true, staged)
            }

            is ReplayResult.BaseMismatch -> {
                conflicted(uri, loaded, replay, staged)
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

    /** The disk is the journal's base: apply the edits and carry on journalling. */
    private suspend fun restored(
        uri: DocumentUri,
        loaded: LoadResult.Loaded,
        text: String,
        damaged: Boolean,
        staged: StagedCopy,
    ): OpenOutcome {
        withContext(context.io) { staged.commit() }
        loaded.document.replaceAllWith(text)
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
                ?: return kept(uri, loaded, null, "file changed and no base copy to replay onto", readOnly = false)
        val baseText = baseDocument.text.toString()
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
        val entry =
            register(
                uri,
                LoadResult.Loaded(baseDocument, journalBase, loaded.metadata, loaded.readOnly, loaded.budget),
                dirty = true,
            )
        entry.conflict = ConflictState(baseText, loaded.document.text.toString(), loaded.fingerprint, recovered = true)
        entry.journal.adopt()
        entry.journal.resume(journalBase, entry.document.snapshot(), replacing = journalBase)
        return OpenOutcome.Opened(entry, Recovery.Conflicted(context.journalTimes[uri] ?: 0))
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
        hasJournal: Boolean,
    ): OpenOutcome =
        when (result) {
            is LoadResult.Declined -> OpenOutcome.TooLarge(result.admission)
            is LoadResult.UnsupportedEncoding -> OpenOutcome.UnsupportedEncoding(result.encoding)
            is LoadResult.Failed -> OpenOutcome.Failed(result.failure, hasJournal)
            is LoadResult.Loaded -> error("not a failure")
        }
}
