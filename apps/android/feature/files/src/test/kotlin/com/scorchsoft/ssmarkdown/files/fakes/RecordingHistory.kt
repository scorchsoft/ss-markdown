package com.scorchsoft.ssmarkdown.files.fakes

import com.scorchsoft.ssmarkdown.files.document.CheckpointReason
import com.scorchsoft.ssmarkdown.files.document.HistoryHook
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.Snapshot

/** Records every history call as "kind:text". */
class RecordingHistory : HistoryHook {
    val entries = mutableListOf<String>()

    override fun onOpened(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    ) {
        entries += "opened:$text"
    }

    override fun onSaved(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    ) {
        entries += "saved:$text"
    }

    override fun onChangedOutside(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    ) {
        entries += "outside:$text"
    }

    override fun onCheckpoint(
        uri: DocumentUri,
        text: Snapshot,
        reason: CheckpointReason,
    ) {
        entries += "checkpoint-$reason:$text"
    }
}
