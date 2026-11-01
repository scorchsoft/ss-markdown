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

    /** A checkpoint of unsaved work, before it is discarded or set aside. */
    fun onCheckpoint(
        uri: DocumentUri,
        text: Snapshot,
        reason: CheckpointReason,
    )

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

                override fun onCheckpoint(
                    uri: DocumentUri,
                    text: Snapshot,
                    reason: CheckpointReason,
                ) = Unit
            }
    }
}
