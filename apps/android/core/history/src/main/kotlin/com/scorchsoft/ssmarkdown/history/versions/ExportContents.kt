package com.scorchsoft.ssmarkdown.history.versions

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * One document inside a history export, as the import picker shows it
 * (docs/ux-spec.md, "Import history": attach "to the matching file by
 * name and content fingerprint, or to a file you pick").
 *
 * @property uri the document's URI on the device that exported it; pass it
 *   as `sourceUri` to [VersionStore.import] to import this document.
 * @property displayName its file name, for the picker row.
 * @property versions how many versions the export holds for it.
 * @property branches how many branches those versions sit on.
 * @property oldestMillis wall time of its oldest version (display only).
 * @property newestMillis wall time of its newest version (display only).
 * @property latestSha256Hex SHA-256 of the newest version's text, so the
 *   picker can match it against a file's current fingerprint with
 *   [VersionStore.findByFingerprint] or a fresh hash of the file.
 */
public data class ExportedDocument(
    public val uri: String,
    public val displayName: String,
    public val versions: Int,
    public val branches: Int,
    public val oldestMillis: Long,
    public val newestMillis: Long,
    public val latestSha256Hex: String?,
)

/** What reading a history `.zip`'s table of contents came to. */
public sealed interface ExportContents {
    /**
     * The manifest was read. Texts are not verified here (that would read
     * every version); [VersionStore.import] verifies each one before it
     * writes anything.
     *
     * @property documents the documents in the export, in manifest order.
     */
    public data class Listed(
        public val documents: List<ExportedDocument>,
    ) : ExportContents

    /**
     * Not a history export, or a malformed one.
     *
     * @property reason what is wrong, for logs.
     */
    public data class Invalid(
        public val reason: String,
    ) : ExportContents

    /**
     * Made by a newer app; this one cannot list or import it.
     *
     * @property formatVersion the version found.
     */
    public data class Unsupported(
        public val formatVersion: Int,
    ) : ExportContents

    /**
     * The zip could not be read.
     *
     * @property cause the failure.
     */
    public data class Failed(
        public val cause: IOException,
    ) : ExportContents
}

/** Reads only the manifest of an export, so a picker opens instantly even for a large zip. */
internal object ExportContentsReader {
    fun read(zipFile: File): ExportContents =
        try {
            ZipFile(zipFile).use { zip -> list(ExportManifest.read(zip)) }
        } catch (e: IllegalArgumentException) {
            ExportContents.Invalid(e.message ?: "malformed export")
        } catch (ignored: StackOverflowError) {
            // A hostile file can still be deep in ways the parser's limit does not see; never crash on one (SEC-4).
            ExportContents.Invalid("export nested too deeply")
        } catch (ignored: OutOfMemoryError) {
            ExportContents.Invalid("export too large to read")
        } catch (e: IOException) {
            ExportContents.Failed(e)
        }

    private fun list(manifest: ExportManifest): ExportContents {
        if (manifest.formatVersion > ExportFormat.VERSION) {
            return ExportContents.Unsupported(manifest.formatVersion.toInt())
        }
        return ExportContents.Listed(manifest.documents.map(::summarise))
    }

    private fun summarise(document: ExportManifest.Document): ExportedDocument {
        val infos = document.versions.map { it.info }
        val newest = infos.maxWithOrNull(compareBy({ it.wallTimeMillis }, { it.id.seq }))
        return ExportedDocument(
            uri = document.uri,
            displayName = DocumentKey(document.uri).displayName,
            versions = infos.size,
            branches = infos.map { it.branch }.distinct().size,
            oldestMillis = infos.minOfOrNull { it.wallTimeMillis } ?: 0L,
            newestMillis = newest?.wallTimeMillis ?: 0L,
            latestSha256Hex = newest?.sha256Hex,
        )
    }
}
