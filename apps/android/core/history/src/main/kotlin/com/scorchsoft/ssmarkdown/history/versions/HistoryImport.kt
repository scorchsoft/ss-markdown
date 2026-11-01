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
 *
 * Verification runs *outside* the store's lock and only the write runs
 * inside it ([locked]), so a large or hostile zip never holds up a
 * checkpoint that "Don't save" is waiting for (R2-DS-7). Each distinct
 * entry is decompressed and hashed once, however many versions name it,
 * and the texts together may declare at most [MAX_TOTAL_CHARS].
 */
internal class HistoryImporter(
    private val directory: HistoryDirectory,
    private val locked: (() -> ImportResult) -> ImportResult = { it() },
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
        val declared = document.versions.sumOf { it.info.textLength.toLong() }
        if (declared > MAX_TOTAL_CHARS) return ImportResult.Invalid("the export's texts are too large together")
        val verified = HashMap<Triple<String, String, Int>, Boolean>()
        val unreadable =
            document.versions.firstOrNull { version ->
                val key = Triple(version.path, version.info.sha256Hex, version.info.textLength)
                !verified.getOrPut(key) { ExportManifest.readText(zip, version) != null }
            }
        if (unreadable != null) return ImportResult.Invalid("${unreadable.path} is missing or does not match its hash")
        return locked { write(zip, document, target) }
    }

    /** The write half: under the store's lock, after every text was verified. */
    private fun write(
        zip: ZipFile,
        document: ExportManifest.Document,
        target: DocumentKey,
    ): ImportResult {
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

    internal companion object {
        /**
         * Most UTF-16 units all of one import's versions may declare
         * together: 1 Gi, far beyond a real document's history, far below
         * what a zip bomb naming one entry 100,000 times would ask for.
         */
        const val MAX_TOTAL_CHARS: Long = 1L shl 30
    }
}
