package com.scorchsoft.ssmarkdown.history.journal

import java.io.IOException

/**
 * Everything replaying a journal can come to. Each case is an expected
 * outcome on a phone that crashes, fills up or gets its storage
 * corrupted, so none of them is an exception. Every case that found any
 * text hands it back: recovery shows what it has, never nothing.
 */
public sealed interface ReplayResult {
    /**
     * Every record applied cleanly.
     *
     * @property text the document as the journal last saw it.
     * @property lastSeq the sequence number of the last record applied, or 0 if there were none.
     * @property recordCount how many records were applied.
     * @property discardedTailBytes bytes of a torn final write that were ignored (0 after a clean close).
     *   A torn tail is what an interrupted append leaves, so it is normal, not damage.
     * @property history the undo and redo steps the journal rebuilt for [text], capped at
     *   [JournalHistory.TAIL_MAX_BYTES]; empty for a version 1 journal, which kept none.
     * @property editsReplayed true when any record changed the text: false for a journal that holds
     *   only undo history (one a save left behind), whose [text] is the base's.
     */
    public data class Ok(
        public val text: String,
        public val lastSeq: Long,
        public val recordCount: Int,
        public val discardedTailBytes: Long,
        public val history: JournalHistory = JournalHistory.EMPTY,
        public val editsReplayed: Boolean = false,
    ) : ReplayResult

    /**
     * A complete record failed its checksum, sequence or parse check, or an
     * edit did not fit the text. Replay stopped there.
     *
     * @property offset byte offset in the file of the record that failed.
     * @property recoveredText the text after the last good record, which is still worth offering.
     * @property lastSeq the sequence number of the last good record, or 0 if none.
     * @property recordCount how many records were applied before the failure.
     * @property reason a short, non-localised description for logs.
     * @property history the undo and redo steps up to the last good record, as for [Ok.history].
     * @property editsReplayed true when any good record changed the text.
     */
    public data class Corrupt(
        public val offset: Long,
        public val recoveredText: String,
        public val lastSeq: Long,
        public val recordCount: Int,
        public val reason: String,
        public val history: JournalHistory = JournalHistory.EMPTY,
        public val editsReplayed: Boolean = false,
    ) : ReplayResult

    /**
     * The journal was written against different bytes from the base the
     * caller provided. Nothing was applied: edits on the wrong base are
     * corruption that looks like text. The caller takes the conflict path
     * (docs/robustness.md §2a).
     *
     * @property journalBase the base the journal names.
     * @property providedBase the base the caller holds.
     */
    public data class BaseMismatch(
        public val journalBase: BaseFingerprint,
        public val providedBase: BaseFingerprint,
    ) : ReplayResult

    /**
     * Written by a newer build in a format this one does not know. The
     * file stays exactly as it is, so an update can still recover it.
     *
     * @property formatVersion the version found in the file.
     */
    public data class Unsupported(
        public val formatVersion: Int,
    ) : ReplayResult

    /**
     * Not a journal, or its header is damaged, so not even its base is
     * known and nothing can safely be applied.
     *
     * @property reason a short, non-localised description for logs.
     */
    public data class Unreadable(
        public val reason: String,
    ) : ReplayResult

    /** There is no journal for this document: nothing to recover. */
    public data object Missing : ReplayResult

    /**
     * The file could not be read at all.
     *
     * @property cause the underlying failure.
     */
    public data class IoFailure(
        public val cause: IOException,
    ) : ReplayResult
}
