package com.scorchsoft.ssmarkdown.history.versions

import java.io.File
import java.io.IOException

/** A clear from the Storage screen or a file's ⋯ menu (docs/ux-spec.md, "Free up space"). */
public sealed interface ClearRequest {
    /**
     * History of files not opened in [days] days.
     *
     * @property days the threshold.
     * @property includePinned also remove pinned versions (the user chose to).
     */
    public data class NotOpenedFor(
        public val days: Int,
        public val includePinned: Boolean = false,
    ) : ClearRequest

    /**
     * Versions older than [days] days, across all files. The latest
     * version of every file (and every imported branch) is always kept,
     * and so are pinned versions.
     *
     * @property days the threshold.
     */
    public data class OlderThan(
        public val days: Int,
    ) : ClearRequest

    /**
     * One file's history.
     *
     * @property document the file.
     * @property includePinned also remove pinned versions.
     */
    public data class OneFile(
        public val document: DocumentKey,
        public val includePinned: Boolean = false,
    ) : ClearRequest

    /**
     * All version history.
     *
     * @property includePinned also remove pinned versions (kept by default).
     */
    public data class Everything(
        public val includePinned: Boolean = false,
    ) : ClearRequest
}

/**
 * What a clear would remove, for its confirmation dialog: "642 saved
 * versions (18.4 MB) of 11 files".
 *
 * @property versions versions it removes.
 * @property files files it touches.
 * @property bytes about how much space it frees.
 */
public data class ClearPreview(
    public val versions: Int,
    public val files: Int,
    public val bytes: Long,
)

/** What a clear did. */
public sealed interface ClearResult {
    /**
     * Done.
     *
     * @property versions versions removed.
     * @property files files touched.
     * @property bytesFreed disk space freed.
     * @property export the verified export made first, or null when none was asked for.
     */
    public data class Cleared(
        public val versions: Int,
        public val files: Int,
        public val bytesFreed: Long,
        public val export: ExportResult.Exported?,
    ) : ClearResult

    /**
     * The export failed, so nothing was deleted.
     *
     * @property export what the export reported.
     */
    public data class ExportFailed(
        public val export: ExportResult,
    ) : ClearResult

    /**
     * A file's history could not be rewritten. Files before it were
     * cleared; it and the rest are untouched (each file is atomic).
     *
     * @property cause the failure.
     * @property versionsCleared versions removed before it.
     */
    public data class Failed(
        public val cause: IOException,
        public val versionsCleared: Int,
    ) : ClearResult
}

/** Turns a [ClearRequest] into the exact versions it removes from each history. */
internal class ClearPlanner(
    private val now: Long,
) {
    /** One history's share of a clear. */
    class Part(
        val history: DocumentHistory,
        val ids: Set<VersionId>,
    ) {
        /** True when the clear empties this history, so all its disk space is freed. */
        val removesAll: Boolean get() = ids.size == history.index.versions.size

        val estimatedBytes: Long
            get() =
                if (removesAll) {
                    history.diskBytes()
                } else {
                    history.index.versions
                        .filter { it.id in ids }
                        .sumOf { it.recordBytes.toLong() }
                }
    }

    fun plan(
        request: ClearRequest,
        histories: List<DocumentHistory>,
        skip: Set<DocumentKey>,
    ): List<Part> =
        histories
            .filter { it.key !in skip }
            .map { Part(it, idsFor(request, it)) }
            .filter { it.ids.isNotEmpty() }

    private fun idsFor(
        request: ClearRequest,
        history: DocumentHistory,
    ): Set<VersionId> {
        val versions = history.index.versions.map { it.info }
        val chosen =
            when (request) {
                is ClearRequest.NotOpenedFor -> {
                    if (now - history.index.lastOpenedMillis >= request.days * RetentionRules.DAY) {
                        versions.filter { request.includePinned || !it.pinned }
                    } else {
                        emptyList()
                    }
                }

                is ClearRequest.OlderThan -> {
                    val latest =
                        history.index.branches.keys
                            .mapNotNull { history.index.head(it)?.id }
                            .toSet()
                    versions.filter {
                        it.id !in latest && !it.pinned && now - it.wallTimeMillis >= request.days * RetentionRules.DAY
                    }
                }

                is ClearRequest.OneFile -> {
                    if (history.key ==
                        request.document
                    ) {
                        versions.filter { request.includePinned || !it.pinned }
                    } else {
                        emptyList()
                    }
                }

                is ClearRequest.Everything -> {
                    versions.filter { request.includePinned || !it.pinned }
                }
            }
        return chosen.map { it.id }.toSet()
    }
}

/** Runs a planned clear: export first if asked, then remove file by file. */
internal class ClearRunner(
    private val pruner: Pruner,
    private val exporter: HistoryExporter,
) {
    @Suppress("ReturnCount") // Export failure and disk failure each stop the clear before more is deleted.
    fun run(
        parts: List<ClearPlanner.Part>,
        exportTo: File?,
        reason: CleanupReason,
    ): ClearResult {
        val export = exportTo?.let { file -> exporter.export(parts.map { it.history to it.ids }, file) }
        if (export != null && export !is ExportResult.Exported) return ClearResult.ExportFailed(export)
        var versions = 0
        var freed = 0L
        for (part in parts) {
            try {
                val entries = pruner.remove(part.history, part.ids, reason)
                versions += entries.filter { it.reason == reason }.sumOf { it.versionsRemoved }
                freed += entries.sumOf { it.bytesFreed }
            } catch (e: IOException) {
                return ClearResult.Failed(e, versions)
            }
        }
        return ClearResult.Cleared(versions, parts.size, freed, export as ExportResult.Exported?)
    }
}
