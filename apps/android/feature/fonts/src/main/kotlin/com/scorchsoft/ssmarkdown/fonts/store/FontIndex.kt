package com.scorchsoft.ssmarkdown.fonts.store

import com.scorchsoft.ssmarkdown.fonts.FontStyleKey
import java.io.File
import java.io.IOException

/**
 * The `font.tsv` file in each family directory: what the family is called,
 * where it came from, and each file's style, size and checksum. Plain
 * tab-separated text, so a person poking at app storage can read it.
 *
 * ```
 * family    Lora
 * source    google
 * installed 1767225600000
 * file      400.ttf  400  0  106132  9f2c…
 * ```
 */
internal object FontIndex {
    const val FILE_NAME = "font.tsv"
    private const val FILE_COLUMNS = 6
    private const val ITALIC_COLUMN = 3
    private const val BYTES_COLUMN = 4
    private const val SHA_COLUMN = 5

    @Suppress("ReturnCount") // Unreadable, incomplete and valid each end differently.
    fun read(directory: File): InstalledFont? {
        val index = File(directory, FILE_NAME)
        val rows =
            try {
                index.readLines().map { it.split('\t') }
            } catch (_: IOException) {
                return null
            }
        val values = rows.filter { it.size == 2 }.associate { it[0] to it[1] }
        val family = values["family"]
        val source = InstalledSource.entries.firstOrNull { it.key == values["source"] }
        val installed = values["installed"]?.toLongOrNull()
        val files = rows.filter { it.firstOrNull() == "file" }.mapNotNull { file(directory, it) }
        if (family == null || source == null) return null
        return installed?.takeIf { files.isNotEmpty() }?.let { InstalledFont(family, source, it, files) }
    }

    fun write(
        directory: File,
        font: InstalledFont,
    ) {
        val text =
            buildString {
                append("family\t").append(font.family).append('\n')
                append("source\t").append(font.source.key).append('\n')
                append("installed\t").append(font.installedAtMillis).append('\n')
                font.files.forEach {
                    append("file\t")
                        .append(it.file.name)
                        .append('\t')
                        .append(it.style.weight)
                        .append('\t')
                    append(if (it.style.italic) 1 else 0)
                        .append('\t')
                        .append(it.bytes)
                        .append('\t')
                        .append(it.sha256)
                    append('\n')
                }
            }
        val temp = File(directory, "$FILE_NAME.tmp")
        temp.writeText(text)
        if (!temp.renameTo(File(directory, FILE_NAME))) throw IOException("Couldn't write the font index in $directory")
    }

    @Suppress("ReturnCount") // A short row and a bad cell are both skipped, before the happy path.
    private fun file(
        directory: File,
        cells: List<String>,
    ): InstalledFile? {
        if (cells.size != FILE_COLUMNS) return null
        val weight = cells[2].toIntOrNull()
        val bytes = cells[BYTES_COLUMN].toLongOrNull()
        val name = cells[1].takeUnless { it.contains('/') || it.startsWith(".") }
        if (weight == null || bytes == null || name == null) return null
        return InstalledFile(
            File(directory, name),
            FontStyleKey(weight, cells[ITALIC_COLUMN] == "1"),
            bytes,
            cells[SHA_COLUMN],
        )
    }
}
