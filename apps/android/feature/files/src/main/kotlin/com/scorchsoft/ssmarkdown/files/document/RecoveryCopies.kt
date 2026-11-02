package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.history.journal.Salvage
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
 * Set-aside edits above this size are called out on the Storage screen,
 * with an export-first clear. The app never deletes them on its own, at
 * any size (robustness §1): the cap decides when to ask, never what to drop.
 */
const val SET_ASIDE_CAP_BYTES: Long = 32L * 1024 * 1024

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

    /**
     * Writes [edits] into [zip] for the user to keep before a clear: each
     * one's rebuilt text (or the typed fragments, labelled as such) and its
     * raw journal. True only when every journal is in the zip byte for byte.
     */
    suspend fun exportSetAside(
        edits: List<SetAsideEdits>,
        zip: File,
    ): Boolean =
        context.journalThread.call {
            try {
                ZipOutputStream(zip.outputStream().buffered()).use { out ->
                    edits.forEachIndexed { index, set -> writeEntries(out, index, set) }
                }
                ZipFile(zip).use { written ->
                    edits.all { set ->
                        val entry = written.getEntry(RAW_FOLDER + set.file.name) ?: return@all false
                        written.getInputStream(entry).use { it.readBytes() }.contentEquals(set.file.readBytes())
                    }
                }
            } catch (_: IOException) {
                false
            }
        }

    /**
     * Deletes [edits] from quarantine: only after the user confirmed, on
     * the Storage screen, and only those listed then. Returns how many went.
     */
    suspend fun deleteSetAside(edits: List<SetAsideEdits>): Int =
        context.journalThread.call { context.journals.deleteSetAside(edits.map { it.file }) }

    private fun writeEntries(
        out: ZipOutputStream,
        index: Int,
        set: SetAsideEdits,
    ) {
        val salvage =
            context.journals.salvageSetAside(set.file) { base ->
                set.uri?.let { context.readBaseText(it, base)?.text?.toString() }
            }
        val stem =
            "%02d-%s".format(
                index + 1,
                set.uri
                    ?.value
                    ?.substringAfterLast('/')
                    ?.ifBlank { null } ?: "unknown",
            )
        val safe = stem.replace(UNSAFE_NAME, "_")
        val text =
            when (salvage) {
                is Salvage.Text -> "$safe.md" to salvage.text
                is Salvage.Fragments -> "$safe-typed-text-only.txt" to salvage.text
                is Salvage.Unavailable -> null
            }
        text?.let { (name, body) ->
            out.putNextEntry(ZipEntry(name))
            out.write(body.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        out.putNextEntry(ZipEntry(RAW_FOLDER + set.file.name))
        set.file.inputStream().use { it.copyTo(out) }
        out.closeEntry()
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

    private companion object {
        const val RAW_FOLDER = "journals/"
        val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]")
    }
}
