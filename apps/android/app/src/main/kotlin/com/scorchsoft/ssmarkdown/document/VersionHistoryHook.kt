package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.files.document.CheckpointReason
import com.scorchsoft.ssmarkdown.files.document.HistoryHook
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.history.versions.VersionStore
import com.scorchsoft.ssmarkdown.text.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Connects the document store's events to version history. The store
 * calls on the main thread with immutable snapshots; each record is
 * handed to [scope] (an I/O scope) so typing never waits on history.
 *
 * Drafts are recorded too: a draft is a document, and "Don't save" on one
 * must still leave a copy in History.
 */
class VersionHistoryHook(
    private val versions: VersionStore,
    private val scope: CoroutineScope,
) : HistoryHook {
    override fun onOpened(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    ) = record(uri, text, VersionKind.Opened)

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

    override fun onCheckpoint(
        uri: DocumentUri,
        text: Snapshot,
        reason: CheckpointReason,
    ) = record(uri, text, VersionKind.Checkpoint, reason.name)

    /** Records [text] for [uri] with an explicit [kind] and [label]: restores, appends, replace-all. */
    fun record(
        uri: DocumentUri,
        text: CharSequence,
        kind: VersionKind,
        label: String? = null,
    ) {
        scope.launch { versions.record(DocumentKey(uri.value), text.toString(), kind, label) }
    }
}
