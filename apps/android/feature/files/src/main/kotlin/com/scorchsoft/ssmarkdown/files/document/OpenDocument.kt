package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.memory.Budget
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.EditGroup
import com.scorchsoft.ssmarkdown.text.EditObserver
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.TextDocument
import kotlinx.coroutines.sync.Mutex

/**
 * One open document, shared by every window that shows its URI
 * (robustness §5a: views never own text). It outlives Activities and is
 * owned by the [DocumentStore].
 *
 * Every op applied to [document], by any caller (the code view typing
 * straight into it, [replace], [undo], [redo]), is journalled through an
 * [EditObserver] **before** it is applied, with its exact chars: that is
 * the whole of the never-lose-work promise at the keystroke level, and
 * it does not depend on any caller remembering to report an edit. The
 * journal's file work happens on the [JournalExecutor], never here.
 * Owned by the main thread, like the [TextDocument] inside it.
 */
@Suppress("LongParameterList") // Built in one place (DocumentOpener) from a load result.
class OpenDocument internal constructor(
    uri: DocumentUri,
    document: TextDocument,
    base: BaseFingerprint,
    /** The provider's display name, if it gave one. */
    val displayName: String?,
    /** The memory budget admission chose. */
    val budget: Budget,
    internal var journal: JournalLink,
    private val notify: (DocumentEvent) -> Unit,
) {
    /** Where the document lives. Changes only on Save as. */
    var uri: DocumentUri = uri
        internal set

    private val journalObserver =
        object : EditObserver {
            // Qualified: in an initializer, a bare `base` would be the constructor's first value, forever.
            override fun beforeEdit(op: EditOp) {
                val owner = this@OpenDocument
                owner.journal.beforeEdit(op, owner.base, owner.isDirty) { owner.document.snapshot() }
            }
        }

    /** The live text. Replaced (never mutated behind your back) by a reload or a conflict resolution. */
    var document: TextDocument = document
        private set

    /** The fingerprint the unsaved edits start from; the disk must match it for a save. */
    var base: BaseFingerprint = base
        internal set

    /** Why editing is refused, or null when editable. */
    var readOnly: ReadOnlyReason? = null
        internal set

    /** A pending conflict, or null. */
    var conflict: ConflictState? = null
        internal set

    /** True once a check found the file deleted or moved; cleared when it is seen again. */
    var fileMissing: Boolean = false
        internal set

    /** How the last save ended, or null if there has not been one. */
    var lastSaveOutcome: SaveOutcome? = null
        internal set

    /** False once a journal write failed: the UI warns that recovery copies may stop. */
    val journalHealthy: Boolean get() = journal.healthy

    /**
     * True while an earlier session's edits that could not be applied
     * ([Recovery.Kept]) are held in place, untouched, for the user to decide.
     */
    val holdsUnappliedEdits: Boolean get() = journal.isHolding

    /** True while there are unsaved edits. */
    val isDirty: Boolean get() = forcedDirty || document.version != cleanVersion

    internal val lock = Mutex()
    internal var windows = 0
    private var cleanVersion = document.version
    private var forcedDirty = false

    init {
        document.addEditObserver(journalObserver)
    }

    /** Replaces `[offset, offset + length)` with [text]; null if the document is read-only. */
    fun replace(
        offset: Int,
        length: Int,
        text: CharSequence,
    ): EditOp? {
        if (readOnly != null) return null
        val op = document.replace(offset, length, text)
        if (!op.isNoOp) onEditsApplied()
        return op
    }

    /** Undoes the last step; null if there is none or the document is read-only. */
    @Suppress("ReturnCount") // Read-only and nothing-to-undo are both null.
    fun undo(): EditGroup? {
        if (readOnly != null || !document.canUndo) return null
        val group = document.undo() ?: return null
        onEditsApplied()
        return group
    }

    /** Redoes the last undone step; null if there is none or the document is read-only. */
    @Suppress("ReturnCount") // Read-only and nothing-to-redo are both null.
    fun redo(): EditGroup? {
        if (readOnly != null || !document.canRedo) return null
        val group = document.redo() ?: return null
        onEditsApplied()
        return group
    }

    /**
     * Tells listeners that edits were applied straight to [document] (the
     * code view types into it): the unsaved dot and autosave hear it. The
     * edits themselves were journalled as they were applied.
     */
    fun onEditsApplied() = notify(DocumentEvent.Edited(uri))

    /** Makes journalled edits durable now: on pause, before a view switch or opening another file. */
    fun flushJournal() = journal.flush()

    internal fun markClean(
        fingerprint: BaseFingerprint,
        savedVersion: Long,
    ) {
        base = fingerprint
        cleanVersion = savedVersion
        forcedDirty = false
        fileMissing = false
    }

    /** Swaps in [replacement]; [dirty] says whether it carries unsaved edits relative to [newBase]. */
    internal fun replaceDocument(
        replacement: TextDocument,
        newBase: BaseFingerprint,
        dirty: Boolean,
    ) {
        document.removeEditObserver(journalObserver)
        replacement.addEditObserver(journalObserver)
        document = replacement
        base = newBase
        cleanVersion = replacement.version
        forcedDirty = dirty
        fileMissing = false
    }
}
