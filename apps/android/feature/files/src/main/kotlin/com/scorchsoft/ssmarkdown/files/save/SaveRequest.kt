package com.scorchsoft.ssmarkdown.files.save

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.Snapshot
import com.scorchsoft.ssmarkdown.text.TextFileFormat

/**
 * Everything a save needs, captured on the editing thread so the pipeline
 * can run on the I/O dispatcher without touching the live document.
 *
 * @property uri where to write.
 * @property text an immutable snapshot of the buffer.
 * @property format the file's encoding details, written back exactly.
 * @property base the fingerprint the edits started from; the disk must still match it.
 * @property readOnly true when this document may not be written at all.
 */
class SaveRequest(
    val uri: DocumentUri,
    val text: Snapshot,
    val format: TextFileFormat,
    val base: BaseFingerprint,
    val readOnly: Boolean = false,
)
