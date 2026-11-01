package com.scorchsoft.ssmarkdown.files.fakes

import com.scorchsoft.ssmarkdown.files.document.CheckpointReason
import com.scorchsoft.ssmarkdown.files.document.CheckpointResult
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

    /** What [onCheckpoint] answers; [CheckpointResult.Off] models History switched off. */
    var checkpointResult = CheckpointResult.Recorded

    /** Runs inside each checkpoint, while the store waits for it: a test types here. */
    var duringCheckpoint: () -> Unit = {}

    override suspend fun onCheckpoint(
        uri: DocumentUri,
        text: Snapshot,
        reason: CheckpointReason,
    ): CheckpointResult {
        entries += "checkpoint-$reason:$text"
        duringCheckpoint()
        return checkpointResult
    }
}
