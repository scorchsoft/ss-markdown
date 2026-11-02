package com.scorchsoft.ssmarkdown.fonts.bundled

import java.io.Reader

/**
 * One row of `MANIFEST.tsv`: what `tools/fetch-fonts.sh` actually wrote.
 *
 * @property file the asset file name.
 * @property family its family.
 * @property italic true for an italic file.
 * @property weights the `wght` range.
 * @property bytes its size.
 * @property sha256 its checksum, lower-case hex.
 * @property subset true when it was subset to Latin (never for a family
 *   with a Reserved Font Name).
 */
data class ManifestEntry(
    val file: String,
    val family: String,
    val italic: Boolean,
    val weights: IntRange,
    val bytes: Long,
    val sha256: String,
    val subset: Boolean,
)

/** Reads the bundled-font manifest. */
object BundledManifest {
    private const val COLUMNS = 8
    private const val WEIGHT_MIN = 3
    private const val WEIGHT_MAX = 4
    private const val BYTES = 5
    private const val SHA = 6
    private const val SUBSET = 7

    /** Parses the manifest; comment lines and malformed rows are skipped. */
    fun parse(reader: Reader): List<ManifestEntry> =
        reader.useLines { lines ->
            lines
                .filterNot { it.isBlank() || it.startsWith("#") }
                .mapNotNull { row(it.split('\t')) }
                .toList()
        }

    private fun row(cells: List<String>): ManifestEntry? {
        val min = cells.getOrNull(WEIGHT_MIN)?.toIntOrNull()
        val max = cells.getOrNull(WEIGHT_MAX)?.toIntOrNull()
        val bytes = cells.getOrNull(BYTES)?.toLongOrNull()?.takeIf { cells.size == COLUMNS }
        return if (min == null || max == null || bytes == null) {
            null
        } else {
            ManifestEntry(cells[0], cells[1], cells[2] == "1", min..max, bytes, cells[SHA], cells[SUBSET] == "yes")
        }
    }
}
