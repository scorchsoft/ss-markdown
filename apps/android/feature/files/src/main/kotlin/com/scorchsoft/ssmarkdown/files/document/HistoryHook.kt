package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.Snapshot

/** Why a checkpoint of unsaved work is being recorded (docs/robustness.md §3). */
enum class CheckpointReason {
    /** Recovered edits were discarded: "Edits discarded · kept in History · Undo". */
    DiscardedEdits,

    /** "Keep the file as it is" in a conflict: your edits stay in History. */
    KeptTheirs,

    /** The document is being closed without saving ("Don't save"). */
    ClosedWithoutSaving,

    /** "Merge my edits" or "Use my version": the buffer as it was, before the result replaced it. */
    BeforeMerge,
}

/** Whether a checkpoint is in History, so the UI may say so (DS-9). */
enum class CheckpointResult {
    /** Recorded and durable: "kept in History" is true. */
    Recorded,

    /** History is switched off: nothing was kept, and the UI must not claim otherwise. */
    Off,

    /** History could not record it (disk full, damaged index). */
    Failed,
}

/**
 * Where the store tells version history what happened. Implemented by the
 * history module's version store; [NONE] when history is switched off
 * (the journal and the base copy work regardless).
 *
 * Called on the store's owner thread with immutable snapshots, so an
 * implementation can hand the work to a background thread.
 */
interface HistoryHook {
    /** "Opened · as found on disk". */
    fun onOpened(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    )

    /** "Saved", after a verified save. */
    fun onSaved(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    )

    /** "Changed outside SS Markdown": the disk version, recorded before it replaces or rebases anything. */
    fun onChangedOutside(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    )

    /**
     * A checkpoint of unsaved work, before it is discarded or set aside.
     * Suspends until the version is durable (or refused), because the
     * caller is about to delete the only other copy (DS-12).
     */
    suspend fun onCheckpoint(
        uri: DocumentUri,
        text: Snapshot,
        reason: CheckpointReason,
    ): CheckpointResult

    /** History switched off. */
    companion object {
        /** Records nothing. */
        val NONE: HistoryHook =
            object : HistoryHook {
                override fun onOpened(
                    uri: DocumentUri,
                    text: Snapshot,
                    fingerprint: BaseFingerprint,
                ) = Unit

                override fun onSaved(
                    uri: DocumentUri,
                    text: Snapshot,
                    fingerprint: BaseFingerprint,
                ) = Unit

                override fun onChangedOutside(
                    uri: DocumentUri,
                    text: Snapshot,
                    fingerprint: BaseFingerprint,
                ) = Unit

                override suspend fun onCheckpoint(
                    uri: DocumentUri,
                    text: Snapshot,
                    reason: CheckpointReason,
                ) = CheckpointResult.Off
            }
    }
}
