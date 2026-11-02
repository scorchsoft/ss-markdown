package com.scorchsoft.ssmarkdown.history.journal

import java.io.File
import java.io.IOException

/** What asking a [JournalStore] for a writer came to. */
public sealed interface OpenResult {
    /**
     * Ready to append.
     *
     * @property writer the journal's writer; close it when the document is closed.
     */
    public data class Opened(
        public val writer: JournalWriter,
    ) : OpenResult

    /**
     * A journal already exists for this document: unsaved edits from an
     * earlier session. The store never starts over one silently. Replay it
     * and [JournalStore.resume], or [JournalStore.markClean] once its text
     * is safe elsewhere.
     *
     * @property documentKey the document whose journal is waiting.
     */
    public data class AlreadyExists(
        public val documentKey: String,
    ) : OpenResult

    /**
     * The existing journal is from a newer format. It is left untouched,
     * so this document is not journalled until the app is updated.
     *
     * @property formatVersion the version found in the file.
     */
    public data class Unsupported(
        public val formatVersion: Int,
    ) : OpenResult

    /**
     * The disk refused.
     *
     * @property cause the underlying failure.
     */
    public data class Failed(
        public val cause: IOException,
    ) : OpenResult
}

/**
 * What the startup scan found: the input to the recovery banner.
 *
 * @property unclean journals with edits that were never saved, one header each.
 * @property unsupported journals from a newer format, kept read-only.
 * @property quarantined files moved to `quarantine/` this time because they could not be parsed.
 * @property failures files that could not be read or moved; left where they are.
 * @property historyOnly clean journals: undo history a save left for a document that was open,
 *   and no unsaved edits. Not offered for recovery; the document's next open restores the history.
 */
public data class StartupScan(
    public val unclean: List<JournalHeader>,
    public val unsupported: List<UnsupportedJournal>,
    public val quarantined: List<File>,
    public val failures: List<IOException>,
    public val historyOnly: List<JournalHeader> = emptyList(),
)

/**
 * A journal this build cannot read.
 *
 * @property file where it is; it is never moved or deleted.
 * @property formatVersion the version it was written in.
 */
public data class UnsupportedJournal(
    public val file: File,
    public val formatVersion: Int,
)

/** What [JournalStore.markClean] did. */
public sealed interface CleanResult {
    /** The journal was sound and is gone. */
    public data object Deleted : CleanResult

    /** There was no journal. */
    public data object NothingToClean : CleanResult

    /**
     * The journal had a damaged record or header, so it may hold edits
     * replay could not reach. It was moved aside, not deleted.
     *
     * @property file where it now lives.
     */
    public data class Quarantined(
        public val file: File,
    ) : CleanResult

    /**
     * The journal is from a newer format and was left exactly where it is.
     *
     * @property formatVersion the version found in the file.
     */
    public data class KeptUnsupported(
        public val formatVersion: Int,
    ) : CleanResult

    /**
     * The disk refused; the journal is still there.
     *
     * @property cause the underlying failure.
     */
    public data class Failed(
        public val cause: IOException,
    ) : CleanResult
}
