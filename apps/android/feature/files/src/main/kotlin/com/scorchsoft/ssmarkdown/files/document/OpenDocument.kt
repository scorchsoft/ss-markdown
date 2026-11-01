package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.memory.Budget
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.history.TextEdit
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.EditGroup
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.TextDocument
import kotlinx.coroutines.sync.Mutex

/**
 * One open document, shared by every window that shows its URI
 * (robustness §5a: views never own text). It outlives Activities and is
 * owned by the [DocumentStore].
 *
 * Every edit goes through [replace], [undo], [redo] or [recordApplied],
 * which journal it before returning: that is the whole of the
 * never-lose-work promise at the keystroke level. Owned by the main
 * thread, like the [TextDocument] inside it.
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

    /** True while there are unsaved edits. */
    val isDirty: Boolean get() = forcedDirty || document.version != cleanVersion

    internal val lock = Mutex()
    internal var windows = 0
    private var cleanVersion = document.version
    private var forcedDirty = false

    /** Replaces `[offset, offset + length)` with [text]; null if the document is read-only. */
    fun replace(
        offset: Int,
        length: Int,
        text: CharSequence,
    ): EditOp? {
        if (readOnly != null) return null
        startJournal()
        val op = document.replace(offset, length, text)
        if (!op.isNoOp) journalOps(listOf(op))
        return op
    }

    /** Undoes the last step; null if there is none or the document is read-only. */
    @Suppress("ReturnCount") // Read-only and nothing-to-undo are both null.
    fun undo(): EditGroup? {
        if (readOnly != null || !document.canUndo) return null
        startJournal()
        val group = document.undo() ?: return null
        journalOps(group.ops.asReversed().map(EditOp::inverse))
        return group
    }

    /** Redoes the last undone step; null if there is none or the document is read-only. */
    @Suppress("ReturnCount") // Read-only and nothing-to-redo are both null.
    fun redo(): EditGroup? {
        if (readOnly != null || !document.canRedo) return null
        startJournal()
        val group = document.redo() ?: return null
        journalOps(group.ops)
        return group
    }

    /**
     * Journals [ops] that the editor applied straight to [document], in
     * the order applied. Call [prepareForEdit] before applying the first.
     */
    fun recordApplied(ops: List<EditOp>) = journalOps(ops.filterNot(EditOp::isNoOp))

    /** Starts the journal before an edit the caller applies itself; see [recordApplied]. */
    fun prepareForEdit() = startJournal()

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
        document = replacement
        base = newBase
        cleanVersion = replacement.version
        forcedDirty = dirty
        fileMissing = false
    }

    private fun startJournal() = journal.ensureStarted(base) { document.text.toString() }

    private fun journalOps(ops: List<EditOp>) {
        if (ops.isEmpty()) return
        ops.forEach { journal.append(TextEdit(it.offset, it.deleted.toString(), it.inserted.toString())) }
        notify(DocumentEvent.Edited(uri))
    }
}
