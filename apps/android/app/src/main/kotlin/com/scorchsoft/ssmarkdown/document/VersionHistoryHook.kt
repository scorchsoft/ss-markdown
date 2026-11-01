package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.files.document.CheckpointReason
import com.scorchsoft.ssmarkdown.files.document.CheckpointResult
import com.scorchsoft.ssmarkdown.files.document.HistoryHook
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.RecordResult
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.history.versions.VersionStore
import com.scorchsoft.ssmarkdown.text.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Connects the document store's events to version history. The store
 * calls on the main thread with immutable snapshots; every record goes
 * through one queue, consumed in order on [scope] (an I/O scope), so
 * typing never waits on history.
 *
 * One queue, not one coroutine per record (DS-12): records made one after
 * another ("Changed outside", then a checkpoint) must land in that order,
 * because the newest is what History calls "latest". Checkpoints are
 * awaited, and their result is the truth the UI may tell: a destructive
 * step deletes the journal only once its checkpoint is durable.
 *
 * Drafts are recorded too: a draft is a document, and "Don't save" on one
 * must still leave a copy in History.
 */
class VersionHistoryHook(
    private val versions: VersionStore,
    scope: CoroutineScope,
) : HistoryHook {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        // One failed record must never stop the queue, or a waiting checkpoint would wait forever.
        scope.launch { for (task in queue) runCatching { task() } }
    }

    override fun onOpened(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    ) = enqueue { versions.recordOpened(DocumentKey(uri.value), fingerprint.sha256Hex) { text.toString() } }

    override fun onSaved(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    ) = record(uri, text, VersionKind.Saved)

    override fun onChangedOutside(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    ) = record(uri, text, VersionKind.ChangedOutside)

    override suspend fun onCheckpoint(
        uri: DocumentUri,
        text: Snapshot,
        reason: CheckpointReason,
    ): CheckpointResult {
        val done = CompletableDeferred<CheckpointResult>()
        enqueue {
            try {
                val result =
                    versions.record(
                        DocumentKey(uri.value),
                        text.toString(),
                        VersionKind.Checkpoint,
                        reason.name,
                    )
                done.complete(result.asCheckpoint())
            } finally {
                done.complete(CheckpointResult.Failed) // No-op once completed.
            }
        }
        return done.await()
    }

    /** Records [text] for [uri] with an explicit [kind] and [label]: restores, appends, replace-all. */
    fun record(
        uri: DocumentUri,
        text: CharSequence,
        kind: VersionKind,
        label: String? = null,
    ) = enqueue { versions.record(DocumentKey(uri.value), text.toString(), kind, label) }

    private fun enqueue(task: suspend () -> Unit) {
        queue.trySend(task)
    }

    private fun RecordResult.asCheckpoint(): CheckpointResult =
        when (this) {
            is RecordResult.Recorded, is RecordResult.Unchanged -> CheckpointResult.Recorded
            RecordResult.Off -> CheckpointResult.Off
            else -> CheckpointResult.Failed
        }
}
