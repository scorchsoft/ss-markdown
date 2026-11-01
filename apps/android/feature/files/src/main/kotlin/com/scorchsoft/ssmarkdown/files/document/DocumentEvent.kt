package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.text.TextDocument

/** Something the UI may want to react to, delivered on the store's owner thread. */
sealed interface DocumentEvent {
    /** Which document. */
    val uri: DocumentUri

    /** The buffer changed (typing, undo, a paste). Drives the unsaved dot and autosave. */
    data class Edited(
        override val uri: DocumentUri,
    ) : DocumentEvent

    /**
     * A save finished; [outcome] says how.
     *
     * @property outcome the pipeline's answer.
     */
    data class SaveFinished(
        override val uri: DocumentUri,
        val outcome: SaveOutcome,
    ) : DocumentEvent

    /**
     * The buffer was replaced by a new [TextDocument] (an outside change on
     * a clean buffer, or a conflict resolved). The old undo stack goes with
     * the old document, by design. [mapOffset] carries a cursor or scroll
     * offset across.
     *
     * @property previous the document that was replaced.
     * @property mapOffset maps an offset in [previous] to the new document.
     */
    class Reloaded(
        override val uri: DocumentUri,
        val previous: TextDocument,
        val mapOffset: (Int) -> Int,
    ) : DocumentEvent

    /** A conflict is now pending: show the "changed since your unsaved edits" sheet. */
    data class ConflictDetected(
        override val uri: DocumentUri,
    ) : DocumentEvent

    /** The file vanished: "File no longer exists — Save as / Recreate". */
    data class FileMissing(
        override val uri: DocumentUri,
    ) : DocumentEvent

    /**
     * Save as moved this document to [uri].
     *
     * @property from where it was.
     */
    data class Moved(
        override val uri: DocumentUri,
        val from: DocumentUri,
    ) : DocumentEvent
}
