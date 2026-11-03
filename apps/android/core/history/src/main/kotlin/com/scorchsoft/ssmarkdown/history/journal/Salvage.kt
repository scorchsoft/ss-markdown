package com.scorchsoft.ssmarkdown.history.journal

import java.io.File

/**
 * What could be rebuilt from a journal that cannot be replayed onto its
 * file: the file is unreachable, changed with no copy of the old bytes,
 * or the journal was set aside (robustness §1, "Edits that cannot be
 * applied are still readable").
 *
 * Salvage never changes the journal. It is for *showing* the text, as a
 * copy the user can read and save elsewhere, never for writing it back
 * over a file.
 */
public sealed interface Salvage {
    /**
     * The whole text as the journal last saw it.
     *
     * @property text the rebuilt document.
     * @property damaged true if a damaged record stopped the rebuild: the text is complete up to it.
     */
    public data class Text(
        public val text: String,
        public val damaged: Boolean,
    ) : Salvage

    /**
     * Neither a snapshot nor the base text was available, so only the
     * text the edits *inserted* could be read, in the order it was typed.
     * Deletions and positions are lost; the words are not.
     *
     * @property text the inserted text, joined in order.
     */
    public data class Fragments(
        public val text: String,
    ) : Salvage

    /**
     * Nothing readable: no journal, a newer format, or a damaged header.
     *
     * @property reason a short, non-localised description for logs.
     */
    public data class Unavailable(
        public val reason: String,
    ) : Salvage
}

/**
 * A journal moved into `quarantine/`: one an earlier session left that
 * could not be applied and was set aside when the document was edited
 * again, or one a check could not vouch for. Never deleted by the app on
 * its own; listed so the user can still open it as a copy.
 *
 * @property file where it is.
 * @property documentKey the document it belongs to, if its header could be read.
 * @property createdAtMillis when its run of edits began, if its header could be read.
 * @property setAsideAtMillis when it was moved aside.
 * @property sizeBytes its size on disk.
 */
public data class SetAsideJournal(
    public val file: File,
    public val documentKey: String?,
    public val createdAtMillis: Long?,
    public val setAsideAtMillis: Long,
    public val sizeBytes: Long,
)

/**
 * Rebuilds as much text as the records allow. A [JournalRecord.Snapshot]
 * replaces everything before it, so a journal that holds one needs no
 * base; before the first snapshot, edits apply to [baseText] when the
 * caller has it. Without either, the inserted text is kept as
 * [Salvage.Fragments].
 */
internal class SalvageState(
    baseText: String?,
) {
    private var text: StringBuilder? = baseText?.let(::StringBuilder)
    private val inserted = StringBuilder()
    private var damaged = false

    /** Applies one record; false once the rebuild must stop. */
    fun apply(record: JournalRecord): Boolean {
        when (record) {
            is JournalRecord.Snapshot -> {
                text = StringBuilder(record.text)
                inserted.setLength(0)
            }

            is JournalRecord.Op -> {
                val current = text
                val edit = record.edit
                if (current != null) {
                    val end = edit.offset + edit.deleted.length
                    val fits =
                        edit.offset >= 0 && end <= current.length && current.substring(edit.offset, end) == edit.deleted
                    if (!fits) return stop()
                    current.replace(edit.offset, end, edit.inserted)
                }
                inserted.append(edit.inserted)
            }

            // Undo history and saves change no text; an undo's own edits are Op records, applied above.
            is JournalRecord.Marker, is JournalRecord.Step, is JournalRecord.History, is JournalRecord.Rebase -> {
                Unit
            }
        }
        return true
    }

    /**
     * The journal outlived a save: from here on the text is [saved], the
     * file that save wrote, whatever the records before it rebuilt.
     */
    fun restartFrom(saved: String) {
        text = StringBuilder(saved)
        inserted.setLength(0)
    }

    /** A damaged record: keep what came before it. */
    fun stop(): Boolean {
        damaged = true
        return false
    }

    fun result(): Salvage {
        val whole = text
        return when {
            whole != null -> Salvage.Text(whole.toString(), damaged)
            inserted.isNotEmpty() -> Salvage.Fragments(inserted.toString())
            else -> Salvage.Unavailable("no snapshot, no base and nothing typed")
        }
    }
}
