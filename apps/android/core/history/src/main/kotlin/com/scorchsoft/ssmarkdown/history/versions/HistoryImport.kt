package com.scorchsoft.ssmarkdown.history.versions

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** What importing a history `.zip` came to. */
public sealed interface ImportResult {
    /**
     * The versions were attached as new branches. The document's own
     * history and the file itself are untouched.
     *
     * @property document the history they were attached to.
     * @property branches the new branches' ids.
     * @property versions how many versions were imported.
     */
    public data class Imported(
        public val document: DocumentKey,
        public val branches: List<Int>,
        public val versions: Int,
    ) : ImportResult

    /**
     * Not a usable export: nothing was imported.
     *
     * @property reason what is wrong, for logs.
     */
    public data class Invalid(
        public val reason: String,
    ) : ImportResult

    /**
     * Made by a newer app, or the target's history is from a newer app.
     *
     * @property formatVersion the version found.
     */
    public data class Unsupported(
        public val formatVersion: Int,
    ) : ImportResult

    /**
     * The disk refused.
     *
     * @property cause the failure.
     */
    public data class Failed(
        public val cause: IOException,
    ) : ImportResult
}

/**
 * Attaches an exported history to a document as separate branches
 * (docs/edge-cases.md, "Import a history zip for a file that has changed
 * since it was exported"). Every text is verified against its hash
 * before anything is written, so a damaged zip imports nothing.
 */
internal class HistoryImporter(
    private val directory: HistoryDirectory,
) {
    fun import(
        zipFile: File,
        target: DocumentKey,
        sourceUri: String?,
    ): ImportResult =
        try {
            ZipFile(zipFile).use { zip -> importFrom(zip, target, sourceUri) }
        } catch (e: IllegalArgumentException) {
            ImportResult.Invalid(e.message ?: "malformed export")
        } catch (ignored: StackOverflowError) {
            // A hostile file can still be deep in ways the parser's limit does not see; never crash on one (SEC-4).
            ImportResult.Invalid("export nested too deeply")
        } catch (ignored: OutOfMemoryError) {
            ImportResult.Invalid("export too large to read")
        } catch (e: IOException) {
            ImportResult.Failed(e)
        }

    @Suppress("ReturnCount") // Each early return is one reason an import is refused before anything is written.
    private fun importFrom(
        zip: ZipFile,
        target: DocumentKey,
        sourceUri: String?,
    ): ImportResult {
        val manifest = ExportManifest.read(zip)
        if (manifest.formatVersion >
            ExportFormat.VERSION
        ) {
            return ImportResult.Unsupported(manifest.formatVersion.toInt())
        }
        val document =
            pick(manifest.documents, sourceUri ?: target.uri)
                ?: return ImportResult.Invalid("the export holds ${manifest.documents.size} documents; name one")
        if (document.versions.isEmpty()) return ImportResult.Invalid("the export holds no versions")
        val unreadable = document.versions.firstOrNull { ExportManifest.readText(zip, it) == null }
        if (unreadable != null) return ImportResult.Invalid("${unreadable.path} is missing or does not match its hash")
        val history =
            when (val open = directory.open(target)) {
                is OpenHistory.Ready -> open.history
                is OpenHistory.Unsupported -> return ImportResult.Unsupported(open.formatVersion)
            }
        val ordered = document.versions.sortedWith(compareBy({ it.info.branch }, { it.info.id.seq }))
        val newSeq = ordered.withIndex().associate { (i, v) -> v.info.id to VersionId(history.index.nextSeq + i) }
        val name = DocumentKey(document.uri).displayName
        val branches =
            ordered.groupBy { it.info.branch }.map { (sourceBranch, versions) ->
                val label = document.branches[sourceBranch]?.takeIf { it.isNotEmpty() } ?: name
                history.addBranch(
                    label,
                    versions.map { version ->
                        BranchVersion(version.info.copy(parent = version.info.parent?.let { newSeq[it] })) {
                            requireNotNull(
                                ExportManifest.readText(zip, version),
                            ) { "${version.path} changed while importing" }
                        }
                    },
                )
            }
        return ImportResult.Imported(target, branches, ordered.size)
    }

    private fun pick(
        documents: List<ExportManifest.Document>,
        uri: String,
    ): ExportManifest.Document? = documents.firstOrNull { it.uri == uri } ?: documents.singleOrNull()
}
