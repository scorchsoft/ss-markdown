package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.history.journal.Salvage
import java.io.File

/** What making a copy of unreachable or unapplied edits came to (R2-DS-2, R2-DS-3). */
sealed interface CopyOutcome {
    /**
     * The edits' text is now in [document], a draft, open and journalled.
     *
     * @property document the draft holding the text.
     * @property complete false when only the typed text could be read
     *   ([Salvage.Fragments]): deletions and positions are lost, and the UI must say so.
     * @property damaged true when a damaged record cut the rebuild short.
     * @property saved true once the draft is verified on disk; only then is the source journal set
     *   aside. Otherwise both stay, and the next launch offers the source again.
     */
    data class Copied(
        val document: OpenDocument,
        val complete: Boolean,
        val damaged: Boolean,
        val saved: Boolean,
    ) : CopyOutcome

    /**
     * Nothing could be read, or the draft could not be opened. Nothing
     * changed: the journal is where it was.
     *
     * @property reason a short, non-localised description for logs.
     */
    data class Unavailable(
        val reason: String,
    ) : CopyOutcome
}

/**
 * A journal in quarantine, as the Storage screen lists it: edits that
 * were set aside (robustness §1). Never deleted by the app; "Open as a
 * copy" brings the text back as a draft.
 *
 * @property uri the document the edits were made to, if the header could be read.
 * @property editedAtMillis when that run of edits began, if known.
 * @property setAsideAtMillis when it was moved aside.
 * @property sizeBytes its size on disk.
 */
data class SetAsideEdits(
    val uri: DocumentUri?,
    val editedAtMillis: Long?,
    val setAsideAtMillis: Long,
    val sizeBytes: Long,
    internal val file: File,
)

/**
 * Turning a journal that cannot be replayed onto its file into a draft
 * the user can read and save elsewhere: the file cannot be reached after
 * a restart (lost grant, moved, deleted, provider gone), or recovery kept
 * the edits aside ([Recovery.Kept]), or they were set aside into
 * quarantine. The journal is only ever moved aside, after the copy is
 * verified on disk; never deleted.
 */
internal class RecoveryCopies(
    private val context: StoreContext,
    private val open: suspend (DocumentUri) -> OpenOutcome,
    private val save: suspend (DocumentUri) -> SaveOutcome,
) {
    /**
     * [uri]'s journal, rebuilt into [draft]. Refused while [uri] is open
     * and running a journal of its own: that journal is this session's
     * edits, which are on screen already.
     */
    suspend fun copy(
        uri: DocumentUri,
        draft: DocumentUri,
    ): CopyOutcome {
        val source = context.entries[uri]
        if (source != null && !source.holdsUnappliedEdits) {
            return CopyOutcome.Unavailable("open, with its own journal")
        }
        // Queued on the owner thread, after the check: any keystroke into the source runs after this read.
        val salvage =
            context.journalThread.call {
                context.journals.salvage(uri.value) { base -> context.readBaseText(uri, base)?.text?.toString() }
            }
        val copied = into(draft, salvage)
        if (copied is CopyOutcome.Copied && copied.saved) setAside(uri)
        return copied
    }

    /** Every journal in quarantine. Blocking reads happen on the journal thread. */
    suspend fun setAsideEdits(): List<SetAsideEdits> =
        context.journalThread.call {
            context.journals.listSetAside().map {
                SetAsideEdits(
                    uri = it.documentKey?.let(::DocumentUri),
                    editedAtMillis = it.createdAtMillis,
                    setAsideAtMillis = it.setAsideAtMillis,
                    sizeBytes = it.sizeBytes,
                    file = it.file,
                )
            }
        }

    /** A set-aside journal, rebuilt into [draft]. The journal stays in quarantine. */
    suspend fun copySetAside(
        edits: SetAsideEdits,
        draft: DocumentUri,
    ): CopyOutcome {
        val salvage =
            context.journalThread.call {
                context.journals.salvageSetAside(edits.file) { base ->
                    edits.uri?.let { context.readBaseText(it, base)?.text?.toString() }
                }
            }
        return into(draft, salvage)
    }

    @Suppress("ReturnCount") // Nothing readable and a draft that will not open each end early.
    private suspend fun into(
        draft: DocumentUri,
        salvage: Salvage,
    ): CopyOutcome {
        val (text, complete, damaged) =
            when (salvage) {
                is Salvage.Text -> Triple(salvage.text, true, salvage.damaged)
                is Salvage.Fragments -> Triple(salvage.text, false, false)
                is Salvage.Unavailable -> return CopyOutcome.Unavailable(salvage.reason)
            }
        val opened = open(draft) as? OpenOutcome.Opened ?: return CopyOutcome.Unavailable("the draft did not open")
        val document = opened.document
        // Through the entry, so the text is journalled under the draft's own key before anything else happens.
        document.replace(0, document.document.text.length, text)
            ?: return CopyOutcome.Unavailable("the draft is read-only")
        val saved = save(draft) is SaveOutcome.Saved
        return CopyOutcome.Copied(document, complete, damaged, saved)
    }

    /** The copy is durable: the source journal moves into quarantine, still listed, so no launch offers it again. */
    private suspend fun setAside(uri: DocumentUri) {
        val source = context.entries[uri]
        if (source != null) {
            source.journal.discardHeld()
        } else {
            context.journalThread.call { runCatching { context.journals.setAside(uri.value) } }
        }
    }
}
