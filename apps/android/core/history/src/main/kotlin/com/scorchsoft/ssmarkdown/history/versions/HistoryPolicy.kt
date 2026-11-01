package com.scorchsoft.ssmarkdown.history.versions

/** A storage cap: a number of bytes, or none at all (the Storage screen's "No limit"). */
public sealed interface StorageLimit {
    /**
     * At most [bytes].
     *
     * @property bytes the cap.
     */
    public data class Bytes(
        public val bytes: Long,
    ) : StorageLimit {
        init {
            require(bytes >= 0) { "a limit must not be negative: $bytes" }
        }
    }

    /** No cap. */
    public data object None : StorageLimit
}

/**
 * The Storage and history settings that version history obeys
 * (docs/ux-spec.md, "Storage"; docs/robustness.md §3).
 *
 * @property recording false when "Keep version history" is off: nothing new is recorded.
 * @property totalLimit cap on all history together; 1 GB by default.
 * @property perFileLimit cap on one file's history; 100 MB by default.
 * @property forgetUnusedAfterDays forget the (unpinned) history of files not opened for this many
 *   days; null is "Never", the default.
 */
public data class HistoryPolicy(
    public val recording: Boolean = true,
    public val totalLimit: StorageLimit = StorageLimit.Bytes(DEFAULT_TOTAL_LIMIT),
    public val perFileLimit: StorageLimit = StorageLimit.Bytes(DEFAULT_PER_FILE_LIMIT),
    public val forgetUnusedAfterDays: Int? = null,
) {
    init {
        require(forgetUnusedAfterDays == null || forgetUnusedAfterDays > 0) { "forgetUnusedAfterDays must be positive" }
    }

    /** The defaults the spec names. */
    public companion object {
        /** 1 GB. */
        public const val DEFAULT_TOTAL_LIMIT: Long = 1_000_000_000

        /** 100 MB. */
        public const val DEFAULT_PER_FILE_LIMIT: Long = 100_000_000
    }
}

/** What to do with existing history when "Keep version history" is turned off. */
public sealed interface ExistingHistory {
    /** Keep it; it stays readable and exportable, nothing new is added. */
    public data object Keep : ExistingHistory

    /** Delete all of it, pinned versions included (the user confirmed). */
    public data object Delete : ExistingHistory

    /**
     * Export everything to [zip] first, verify the export, then delete.
     *
     * @property zip where the export goes.
     */
    public data class ExportThenDelete(
        public val zip: java.io.File,
    ) : ExistingHistory
}
