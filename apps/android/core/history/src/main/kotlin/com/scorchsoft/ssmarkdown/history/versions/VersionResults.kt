package com.scorchsoft.ssmarkdown.history.versions

import java.io.IOException

/** What [VersionStore.record] did. */
public sealed interface RecordResult {
    /**
     * A new version was written.
     *
     * @property version its metadata.
     */
    public data class Recorded(
        public val version: VersionInfo,
    ) : RecordResult

    /**
     * The text is the same as the latest version, so nothing new was stored.
     *
     * @property latest the version that already holds it.
     */
    public data class Unchanged(
        public val latest: VersionId,
    ) : RecordResult

    /** History is switched off; nothing was recorded. */
    public data object Off : RecordResult

    /**
     * The history on disk is from a newer app and is left untouched.
     *
     * @property formatVersion the version found.
     */
    public data class Unsupported(
        public val formatVersion: Int,
    ) : RecordResult

    /**
     * The disk refused; the history is as it was before the call.
     *
     * @property cause the failure.
     */
    public data class Failed(
        public val cause: IOException,
    ) : RecordResult
}

/** What [VersionStore.read] found. */
public sealed interface ReadResult {
    /**
     * The version's exact text, verified against its SHA-256.
     *
     * @property text the text.
     * @property version its metadata.
     */
    public data class Text(
        public val text: String,
        public val version: VersionInfo,
    ) : ReadResult

    /**
     * The version's chain is damaged and it cannot be rebuilt.
     *
     * @property id the version asked for.
     * @property fallback the nearest version that can still be read (an intact keyframe), or null.
     */
    public data class Corrupt(
        public val id: VersionId,
        public val fallback: VersionId?,
    ) : ReadResult

    /** No such version (or no history for the document). */
    public data object NotFound : ReadResult

    /**
     * The history is from a newer app.
     *
     * @property formatVersion the version found.
     */
    public data class Unsupported(
        public val formatVersion: Int,
    ) : ReadResult
}

/** A document's history as History shows it. */
public sealed interface HistoryListing {
    /**
     * Readable history.
     *
     * @property versions every version, by branch, oldest first.
     * @property branches branch labels by id; the main branch's is empty.
     * @property usage what it costs on disk.
     */
    public data class Listed(
        public val versions: List<VersionInfo>,
        public val branches: Map<Int, String>,
        public val usage: FileUsage,
    ) : HistoryListing

    /** Nothing recorded for this document. */
    public data object Empty : HistoryListing

    /**
     * From a newer app; read-only and untouched.
     *
     * @property formatVersion the version found.
     */
    public data class Unsupported(
        public val formatVersion: Int,
    ) : HistoryListing

    /** The index is damaged; it is quarantined the next time a version is recorded. */
    public data object Unreadable : HistoryListing
}

/**
 * One file's row on the Storage screen.
 *
 * @property document the file.
 * @property bytes bytes on disk (index plus pack).
 * @property versions how many versions it holds.
 * @property pinned how many of them are pinned.
 * @property lastOpenedMillis when it was last opened (wall time), or 0 if never recorded.
 */
public data class FileUsage(
    public val document: DocumentKey,
    public val bytes: Long,
    public val versions: Int,
    public val pinned: Int,
    public val lastOpenedMillis: Long,
)

/**
 * The Storage screen's "Version history (n files)" figure and its rows.
 *
 * @property files one row per document, largest first.
 * @property totalBytes their sum.
 */
public data class StorageUsage(
    public val files: List<FileUsage>,
    public val totalBytes: Long,
)

/** What changing one version's metadata (pinning, naming) did. */
public sealed interface UpdateResult {
    /**
     * Done.
     *
     * @property version the new metadata.
     */
    public data class Updated(
        public val version: VersionInfo,
    ) : UpdateResult

    /** No such version. */
    public data object NotFound : UpdateResult

    /**
     * Not changed.
     *
     * @property cause why.
     */
    public data class Failed(
        public val cause: IOException,
    ) : UpdateResult
}
