package com.scorchsoft.ssmarkdown.history.versions

import java.io.IOException
import java.util.zip.ZipFile

/**
 * A history export's manifest, parsed and checked. Shared by import and
 * by the exporter's own verification, so both read the format one way.
 */
internal class ExportManifest(
    val formatVersion: Long,
    val documents: List<Document>,
) {
    class Document(
        val uri: String,
        val branches: Map<Int, String>,
        val versions: List<Version>,
    )

    /**
     * @property info the version's metadata as exported (storage fields zeroed).
     * @property path the zip entry with its text.
     */
    class Version(
        val info: VersionInfo,
        val path: String,
    )

    companion object {
        private const val SHA_HEX = 64

        /**
         * Reads the manifest. Throws [IllegalArgumentException] when it is
         * missing or malformed, [IOException] when the zip cannot be read.
         * A newer [formatVersion] is returned as is; callers check it.
         */
        fun read(zip: ZipFile): ExportManifest {
            val entry = requireNotNull(zip.getEntry(ExportFormat.MANIFEST)) { "no manifest" }
            val json = Json.parse(String(ExportFormat.read(zip, entry, ExportFormat.MAX_MANIFEST_BYTES))) as? Map<*, *>
            requireNotNull(json) { "manifest is not an object" }
            require(json.string("format") == ExportFormat.FORMAT) { "not a history export" }
            val version = json.long("formatVersion")
            if (version > ExportFormat.VERSION) return ExportManifest(version, emptyList())
            val documents = json.list("documents").map { document(requireNotNull(it as? Map<*, *>) { "bad document" }) }
            return ExportManifest(version, documents)
        }

        /** The verified text of [version], or null when it is missing, too long or does not match its hash. */
        @Suppress("ReturnCount") // Missing entry, oversized entry, then the verified text.
        fun readText(
            zip: ZipFile,
            version: Version,
        ): String? {
            val entry = zip.getEntry(version.path) ?: return null
            val bytes =
                try {
                    ExportFormat.read(zip, entry, TextHash.maxBytes(version.info.textLength))
                } catch (ignored: IOException) {
                    return null
                }
            return TextHash.text(bytes)?.takeIf {
                it.length == version.info.textLength && TextHash.sha256Hex(bytes) == version.info.sha256Hex
            }
        }

        private fun document(json: Map<*, *>): Document {
            val branches =
                json.list("branches").associate {
                    val branch = requireNotNull(it as? Map<*, *>) { "bad branch" }
                    branch.int("id") to branch.string("label")
                }
            val versions = json.list("versions").map { version(requireNotNull(it as? Map<*, *>) { "bad version" }) }
            return Document(json.string("uri"), branches, versions)
        }

        private fun version(json: Map<*, *>): Version {
            val sha = json.string("sha256")
            require(sha.length == SHA_HEX && sha.all { it in '0'..'9' || it in 'a'..'f' }) { "bad sha256" }
            val length = json.int("length")
            require(length >= 0) { "bad length" }
            val info =
                VersionInfo(
                    id = VersionId(json.long("seq")),
                    branch = json.int("branch"),
                    wallTimeMillis = json.long("time"),
                    kind =
                        requireNotNull(
                            VersionKind.entries.firstOrNull { it.name == json.string("kind") },
                        ) { "bad kind" },
                    label = json.stringOrNull("label"),
                    pinned = json.boolean("pinned"),
                    parent = json.longOrNull("parent")?.let { VersionId(it) },
                    changes = LineChangeSummary(json.int("added"), json.int("removed")),
                    textLength = length,
                    sha256Hex = sha,
                    storedBytes = 0,
                    keyframe = false,
                )
            return Version(info, json.string("path"))
        }
    }
}
