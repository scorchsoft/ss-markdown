package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.history.TextEdit
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.history.journal.CleanResult
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import com.scorchsoft.ssmarkdown.history.journal.JournalWriteResult
import com.scorchsoft.ssmarkdown.history.journal.JournalWriter
import com.scorchsoft.ssmarkdown.history.journal.OpenResult

/**
 * One document's connection to the recovery journal (robustness §1): it
 * exists exactly while the document has unsaved edits.
 *
 * The journal is started lazily, just before the first edit is applied,
 * against the document's base; it is ended only after a verified save
 * or a deliberate discard. Any journal failure flips [healthy] so the UI
 * can say "recovery copies may stop" instead of failing silently.
 */
internal class JournalLink(
    private val store: JournalStore,
    private val key: String,
) {
    private var writer: JournalWriter? = null

    /** False once any journal write has failed; the edits are still in memory. */
    var healthy: Boolean = true
        private set

    /** Starts the journal if it is not running. [currentText] is the text before the coming edit. */
    fun ensureStarted(
        base: BaseFingerprint,
        currentText: () -> String,
    ) {
        if (writer != null) return
        when (val result = store.start(key, base)) {
            is OpenResult.Opened -> writer = result.writer

            // An old journal we did not recover (newer format, damaged or on a changed file):
            // resume() quarantines it rather than writing over it.
            is OpenResult.AlreadyExists -> resume(base, currentText())

            else -> healthy = false
        }
    }

    /** Replaces the journal with a snapshot of [text] on [base]; used after recovery. */
    fun resume(
        base: BaseFingerprint,
        text: String,
    ) {
        writer?.close()
        writer = null
        when (val result = store.resume(key, base, text)) {
            is OpenResult.Opened -> writer = result.writer
            else -> healthy = false
        }
    }

    /** Records an applied edit. */
    fun append(edit: TextEdit) {
        val result = writer?.append(edit) ?: return
        if (result !is JournalWriteResult.Written) healthy = false
    }

    /** Makes everything durable now: on pause and before any risky step. */
    fun flush() {
        val result = writer?.flush() ?: return
        if (result !is JournalWriteResult.Written) healthy = false
    }

    /** Ends the journal after a verified save or a recorded discard. False if it could not be removed. */
    fun endClean(): Boolean {
        writer?.close()
        writer = null
        return when (store.markClean(key)) {
            CleanResult.Deleted, CleanResult.NothingToClean -> true
            else -> false
        }
    }

    /**
     * After a save that the user typed through: the saved part is safe, so
     * the journal starts again on the new [base] with [text] as its
     * snapshot. Between the two steps only the last moment's typing is
     * exposed, as with any group commit.
     */
    fun restartOn(
        base: BaseFingerprint,
        text: String,
    ) {
        endClean()
        when (val result = store.start(key, base)) {
            is OpenResult.Opened -> {
                writer = result.writer
                if (result.writer.compact(text) !is JournalWriteResult.Written) healthy = false
            }

            else -> {
                healthy = false
            }
        }
    }

    /** Releases the file but keeps it: the document is closing dirty or the app is going away. */
    fun close() {
        writer?.close()
        writer = null
    }
}
