package com.scorchsoft.ssmarkdown.history.versions

import java.io.File
import java.io.IOException

/**
 * Local version history for every document: the public face of
 * docs/robustness.md §3.
 *
 * Each document gets a pack of version bodies and an index. The newest
 * version of a branch is stored whole and older ones as backward line
 * deltas, with Mercurial's keyframe rule bounding every chain. Every
 * version carries the SHA-256 of its text, and every read is verified
 * against it. Every write appends to the pack and then commits by
 * renaming the index, so a crash leaves the old history or the new one.
 *
 * Retention (thinning and limits) runs only when [enforceRetention] is
 * called, typically from a background job; it never removes a file's
 * latest version, a pinned version or, for limits, anything from the
 * last 7 days, and every removal lands in [cleanupLog].
 *
 * All methods do blocking I/O; call them off the main thread. They are
 * serialised internally, so one store can be shared by every screen.
 *
 * @param root a private directory for history alone (app `filesDir`, never the cache).
 * @param clock wall time for display, thinning and "not opened in"; ordering never depends on it.
 * @param policy the initial Storage and history settings.
 */
@Suppress("TooManyFunctions") // The whole Storage-and-history contract; each member delegates to one small class.
public class VersionStore(
    root: File,
    private val clock: VersionClock = VersionClock.SYSTEM,
    policy: HistoryPolicy = HistoryPolicy(),
) {
    private val lock = Any()
    private val directory = HistoryDirectory(root)
    private val exporter = HistoryExporter(clock)

    /** Every removal, automatic or asked for: Storage → Recent clean-ups. */
    public val cleanupLog: CleanupLog = CleanupLog(File(root, "cleanup-log.jsonl"))

    private val pruner = Pruner(cleanupLog, clock)

    /** The settings in force. */
    @Volatile
    public var policy: HistoryPolicy = policy
        private set

    /** Applies new Storage and history settings; limits take effect at the next [enforceRetention]. */
    public fun updatePolicy(policy: HistoryPolicy) {
        this.policy = policy
    }

    /**
     * Records [text] as the newest version of [document]. A text equal to
     * the latest version records nothing (an Opened just updates "last
     * opened"). With history off, nothing is recorded.
     *
     * @param parent the version this one derives from; null means the latest. An outside change
     *   passes the version it diverged from, so it shows as its own node.
     */
    public fun record(
        document: DocumentKey,
        text: String,
        kind: VersionKind,
        label: String? = null,
        parent: VersionId? = null,
    ): RecordResult =
        synchronized(lock) {
            if (!policy.recording) return RecordResult.Off
            try {
                when (val open = directory.open(document)) {
                    is OpenHistory.Unsupported -> {
                        RecordResult.Unsupported(open.formatVersion)
                    }

                    is OpenHistory.Ready -> {
                        val result = open.history.append(VersionDraft(text, kind, label, parent, clock.nowMillis()))
                        compactQuietly(open.history)
                        result
                    }
                }
            } catch (e: IOException) {
                RecordResult.Failed(e)
            }
        }

    /**
     * Records an "Opened" version of [document] whose file bytes hash to
     * [fileSha256Hex], building the text with [text] only when it is
     * needed (PERF-14). Re-opening an unchanged file, the common case,
     * then costs one index read instead of a copy and a hash of the whole
     * text. The shortcut is exact: a version's hash is of its text's
     * UTF-8 bytes, so it equals the file's hash only when those are the
     * file's bytes. A file with a BOM or CRLF endings never matches and is
     * recorded the ordinary way.
     */
    public fun recordOpened(
        document: DocumentKey,
        fileSha256Hex: String,
        text: () -> String,
    ): RecordResult =
        synchronized(lock) {
            if (!policy.recording) return RecordResult.Off
            val unchanged =
                try {
                    (directory.open(document) as? OpenHistory.Ready)
                        ?.history
                        ?.openedUnchanged(fileSha256Hex, clock.nowMillis())
                } catch (e: IOException) {
                    return RecordResult.Failed(e)
                }
            unchanged?.let { RecordResult.Unchanged(it) } ?: record(document, text(), VersionKind.Opened)
        }

    /**
     * Compacts after a record when the pack is mostly garbage. The version
     * is already committed, so a failure here only postpones compaction.
     */
    private fun compactQuietly(history: DocumentHistory) {
        if (!history.needsCompaction) return
        try {
            pruner.remove(history, emptySet(), CleanupReason.Damaged)
        } catch (ignored: IOException) {
            // Retried after the next record or retention pass; nothing is lost meanwhile.
        }
    }

    /** [document]'s versions for the History screen. */
    public fun list(document: DocumentKey): HistoryListing =
        synchronized(lock) {
            when (val load = directory.load(document)) {
                is HistoryLoad.Loaded -> {
                    val index = load.history.index
                    if (index.versions.isEmpty()) {
                        HistoryListing.Empty
                    } else {
                        HistoryListing.Listed(index.versions.map { it.info }, index.branches, load.history.usage())
                    }
                }

                HistoryLoad.Missing -> {
                    HistoryListing.Empty
                }

                is HistoryLoad.Unsupported -> {
                    HistoryListing.Unsupported(load.formatVersion)
                }

                HistoryLoad.Corrupt -> {
                    HistoryListing.Unreadable
                }
            }
        }

    /** The exact, verified text of version [id] of [document]: for Compare and Restore. */
    public fun read(
        document: DocumentKey,
        id: VersionId,
    ): ReadResult =
        synchronized(lock) {
            when (val load = directory.load(document)) {
                is HistoryLoad.Loaded -> load.history.read(id)
                is HistoryLoad.Unsupported -> ReadResult.Unsupported(load.formatVersion)
                HistoryLoad.Missing, HistoryLoad.Corrupt -> ReadResult.NotFound
            }
        }

    /** Pins ("Keep forever") or unpins a version, optionally naming it. */
    public fun setPinned(
        document: DocumentKey,
        id: VersionId,
        pinned: Boolean,
        label: String? = null,
    ): UpdateResult =
        synchronized(lock) {
            val history = (directory.load(document) as? HistoryLoad.Loaded)?.history ?: return UpdateResult.NotFound
            try {
                history.update(id) { it.copy(pinned = pinned, label = label ?: it.label) }
            } catch (e: IOException) {
                UpdateResult.Failed(e)
            }
        }

    /** Every file's history, largest first, for the Storage screen. */
    public fun usage(): StorageUsage =
        synchronized(lock) {
            val files = directory.all().map { it.usage() }.sortedByDescending { it.bytes }
            StorageUsage(files, files.sumOf { it.bytes })
        }

    /**
     * Documents whose latest version has this SHA-256 (of the text's
     * WTF-8 bytes): the content-fingerprint half of a history's identity,
     * for finding a moved or renamed file's history.
     */
    public fun findByFingerprint(sha256Hex: String): List<DocumentKey> =
        synchronized(lock) {
            directory
                .all()
                .filter {
                    it.index
                        .head()
                        ?.info
                        ?.sha256Hex == sha256Hex.lowercase()
                }.map { it.key }
        }

    /**
     * Copies [from]'s history to [to] for "Save as". Fails rather than
     * overwrite history [to] already has. True when there was history to copy.
     */
    public fun copyHistory(
        from: DocumentKey,
        to: DocumentKey,
    ): Result<Boolean> =
        synchronized(lock) {
            val source = (directory.load(from) as? HistoryLoad.Loaded)?.history ?: return Result.success(false)
            if (directory.load(to) != HistoryLoad.Missing) {
                return Result.failure(IOException("${to.uri} already has history"))
            }
            runCatching { source.copyTo(directory.directoryFor(to), to.uri) }.map { true }
        }

    /** Thins by the schedule and enforces the limits now. */
    public fun enforceRetention(): RetentionReport =
        synchronized(lock) { RetentionRunner(directory, pruner, policy, clock.nowMillis()).run() }

    /**
     * What [clear] would remove, for its confirmation dialog. Files in
     * [skip] (open with unsaved edits) are left out.
     */
    public fun previewClear(
        request: ClearRequest,
        skip: Set<DocumentKey> = emptySet(),
    ): ClearPreview =
        synchronized(lock) {
            val parts = ClearPlanner(clock.nowMillis()).plan(request, directory.all(), skip)
            ClearPreview(parts.sumOf { it.ids.size }, parts.size, parts.sumOf { it.estimatedBytes })
        }

    /**
     * Runs a clear. With [exportFirst], the versions it removes are
     * exported there and verified before anything is deleted.
     */
    public fun clear(
        request: ClearRequest,
        skip: Set<DocumentKey> = emptySet(),
        exportFirst: File? = null,
    ): ClearResult = clearAs(request, skip, exportFirst, CleanupReason.Cleared)

    private fun clearAs(
        request: ClearRequest,
        skip: Set<DocumentKey>,
        exportFirst: File?,
        reason: CleanupReason,
    ): ClearResult =
        synchronized(lock) {
            val parts = ClearPlanner(clock.nowMillis()).plan(request, directory.all(), skip)
            ClearRunner(pruner, exporter).run(parts, exportFirst, reason)
        }

    /** Exports the whole history of each of [documents] to [target], verified. */
    public fun export(
        documents: List<DocumentKey>,
        target: File,
    ): ExportResult =
        synchronized(lock) {
            val histories = documents.mapNotNull { (directory.load(it) as? HistoryLoad.Loaded)?.history }
            exporter.export(histories.map { it to null }, target)
        }

    /**
     * Imports an export into [target]'s history as separate branches;
     * the current file and its own history are never modified.
     *
     * @param sourceUri which document of a multi-document export to take; by default the one
     *   matching [target], or the only one.
     */
    public fun import(
        zip: File,
        target: DocumentKey,
        sourceUri: String? = null,
    ): ImportResult = synchronized(lock) { HistoryImporter(directory).import(zip, target, sourceUri) }

    /**
     * Lists the documents inside an export without importing anything,
     * for the import picker. Reads only the manifest; pass a listed
     * document's [ExportedDocument.uri] to [import] as `sourceUri`.
     */
    public fun listExport(zip: File): ExportContents = ExportContentsReader.read(zip)

    /**
     * Switches history off ("Keep version history" → off) and deals with
     * what exists as the user chose. Recording stops either way.
     */
    public fun turnOff(existing: ExistingHistory): ClearResult {
        updatePolicy(policy.copy(recording = false))
        return when (existing) {
            ExistingHistory.Keep -> {
                ClearResult.Cleared(0, 0, 0, null)
            }

            ExistingHistory.Delete -> {
                clearAs(ClearRequest.Everything(includePinned = true), emptySet(), null, CleanupReason.HistoryOff)
            }

            is ExistingHistory.ExportThenDelete -> {
                clearAs(
                    ClearRequest.Everything(includePinned = true),
                    emptySet(),
                    existing.zip,
                    CleanupReason.HistoryOff,
                )
            }
        }
    }
}
