package com.scorchsoft.ssmarkdown.history.versions

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** What exporting history to a `.zip` came to. */
public sealed interface ExportResult {
    /**
     * Written and verified: every text was read back from the zip and
     * matched its SHA-256.
     *
     * @property file the zip.
     * @property documents documents in it.
     * @property versions versions in it.
     * @property bytes its size.
     * @property damagedSkipped versions left out because their stored data was already damaged.
     */
    public data class Exported(
        public val file: File,
        public val documents: Int,
        public val versions: Int,
        public val bytes: Long,
        public val damagedSkipped: Int,
    ) : ExportResult

    /**
     * Not exported; nothing was left at the target.
     *
     * @property cause why.
     */
    public data class Failed(
        public val cause: IOException,
    ) : ExportResult
}

/** The `.zip` layout shared by export and import. See VERSIONS-FORMAT.md, "Export". */
internal object ExportFormat {
    const val FORMAT: String = "ss-markdown-history"
    const val VERSION: Long = 1
    const val MANIFEST: String = "manifest.json"

    /** Upper bound on the manifest, so a hostile zip cannot ask for unbounded memory. */
    const val MAX_MANIFEST_BYTES: Int = 64 * 1024 * 1024

    fun textPath(
        document: Int,
        seq: Long,
    ): String = "d$document/v$seq.txt"

    /** Reads at most [limit] bytes of [entry], or throws [IOException] if it is longer. */
    fun read(
        zip: ZipFile,
        entry: ZipEntry,
        limit: Int,
    ): ByteArray {
        zip.getInputStream(entry).use { input -> return readBounded(input, limit) }
    }

    private fun readBounded(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        val bytes = input.readNBytes(limit)
        if (input.read() != -1) throw IOException("zip entry larger than $limit bytes")
        return bytes
    }
}

/**
 * Writes histories to a zip of plain texts plus a JSON manifest, and
 * verifies it before handing it over. The zip is built as `<name>.part`
 * and only renamed to its final name once every text has been read back
 * and matched against its hash: "Nothing is deleted until the export
 * has finished and been verified" (docs/ux-spec.md).
 */
internal class HistoryExporter(
    private val clock: VersionClock,
) {
    /** Exports, for each history, the versions in its set (null: all of them). */
    fun export(
        selection: List<Pair<DocumentHistory, Set<VersionId>?>>,
        target: File,
    ): ExportResult {
        val part = File(target.path + ".part")
        return try {
            var versions = 0
            var damaged = 0
            ZipOutputStream(FileOutputStream(part)).use { zip ->
                val documents =
                    selection.mapIndexed { i, (history, ids) ->
                        val written = writeDocument(zip, i, history, ids)
                        damaged += written.second
                        versions += written.first.list("versions").size
                        written.first
                    }
                val manifest =
                    linkedMapOf(
                        "format" to ExportFormat.FORMAT,
                        "formatVersion" to ExportFormat.VERSION,
                        "exportedAt" to clock.nowMillis(),
                        "documents" to documents,
                    )
                zip.putNextEntry(ZipEntry(ExportFormat.MANIFEST))
                zip.write(Json.write(manifest).toByteArray())
                zip.closeEntry()
            }
            if (!verify(part)) throw IOException("export did not verify")
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            ExportResult.Exported(target, selection.size, versions, target.length(), damaged)
        } catch (e: IOException) {
            part.delete()
            ExportResult.Failed(e)
        }
    }

    private fun writeDocument(
        zip: ZipOutputStream,
        document: Int,
        history: DocumentHistory,
        ids: Set<VersionId>?,
    ): Pair<Map<String, Any?>, Int> {
        val versions = ArrayList<Pair<VersionInfo, String>>()
        var damaged = 0
        for (branch in history.index.branches.keys
            .sorted()) {
            history.walk(branch) { version, text ->
                if (ids == null || version.id in ids) {
                    if (text == null) {
                        damaged++
                    } else {
                        val path = ExportFormat.textPath(document, version.id.seq)
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(TextHash.bytes(text))
                        zip.closeEntry()
                        versions += version.info to path
                    }
                }
            }
        }
        versions.sortWith(compareBy({ it.first.branch }, { it.first.id.seq }))
        val json =
            linkedMapOf(
                "uri" to history.index.uri,
                "branches" to
                    history.index.branches
                        .toSortedMap()
                        .map { (id, label) -> mapOf("id" to id, "label" to label) },
                "versions" to versions.map { (info, path) -> versionJson(info, path) },
            )
        return json to damaged
    }

    private fun versionJson(
        info: VersionInfo,
        path: String,
    ): Map<String, Any?> =
        linkedMapOf(
            "seq" to info.id.seq,
            "branch" to info.branch,
            "time" to info.wallTimeMillis,
            "kind" to info.kind.name,
            "label" to info.label,
            "pinned" to info.pinned,
            "parent" to info.parent?.seq,
            "added" to info.changes.added,
            "removed" to info.changes.removed,
            "length" to info.textLength,
            "sha256" to info.sha256Hex,
            "path" to path,
        )

    private fun verify(file: File): Boolean =
        try {
            ZipFile(file).use { zip ->
                ExportManifest.read(zip).documents.all { document ->
                    document.versions.all { ExportManifest.readText(zip, it) != null }
                }
            }
        } catch (ignored: IllegalArgumentException) {
            false
        }
}
