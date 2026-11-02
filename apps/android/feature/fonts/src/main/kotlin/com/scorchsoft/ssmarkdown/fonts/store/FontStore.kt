package com.scorchsoft.ssmarkdown.fonts.store

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Downloaded and imported fonts on disk, one directory per family:
 *
 * ```
 * <root>/google-lora/400.ttf, 700i.ttf, …, font.tsv
 * <root>/imported-my-face/400.ttf, font.tsv
 * <root>/.staging-<uuid>/        (a download in progress)
 * ```
 *
 * `<root>` is `filesDir/fonts` (feature/files' StorageLayout.fonts), so
 * fonts survive *Clear cache* and are counted on the Storage screen.
 * A family appears only once its directory is complete: files are written
 * into a staging directory and renamed into place in one step, so a crash
 * or cancel mid-download never leaves half a family behind.
 *
 * @param root the fonts directory; created on first write.
 */
class FontStore(
    val root: File,
) {
    /** Every complete installed family; unreadable directories are skipped. */
    fun installed(): List<InstalledFont> =
        root
            .listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            .orEmpty()
            .mapNotNull { FontIndex.read(it) }
            .sortedBy { it.family.lowercase() }

    /** The installed family named [family] (case-insensitive), the most recent if both sources have it. */
    fun find(family: String): InstalledFont? =
        installed().filter { it.family.equals(family.trim(), ignoreCase = true) }.maxByOrNull { it.installedAtMillis }

    /** Bytes used by installed fonts, for Settings → Storage. */
    fun totalBytes(): Long = installed().sumOf { it.bytes }

    /** True when every file of [font] still has the size and checksum it was written with. */
    fun verify(font: InstalledFont): Boolean =
        font.files.all { it.file.isFile && it.file.length() == it.bytes && Sha256.of(it.file) == it.sha256 }

    /** Removes [family] from every source. Returns true if anything was removed. */
    fun delete(family: String): Boolean =
        installed()
            .filter { it.family.equals(family.trim(), ignoreCase = true) }
            .map { directoryFor(it.family, it.source).deleteRecursively() }
            .any { it }

    /** A fresh, empty staging directory. Throws [IOException] if it cannot be made. */
    internal fun stage(): File {
        val dir = File(root, ".staging-${UUID.randomUUID()}")
        if (!dir.mkdirs()) throw IOException("Couldn't create $dir")
        return dir
    }

    /**
     * Moves a complete [staging] directory into place as [font], replacing
     * any earlier copy from the same source. [font]'s files must be inside
     * [staging]; the returned font points at their final location.
     */
    internal fun commit(
        staging: File,
        font: InstalledFont,
    ): InstalledFont {
        val target = directoryFor(font.family, font.source)
        val moved = font.copy(files = font.files.map { it.copy(file = File(target, it.file.name)) })
        FontIndex.write(staging, moved)
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw IOException("Couldn't move $staging to $target")
        return moved
    }

    /** Removes staging directories left by a crash or a cancelled download. */
    fun sweepStaging() {
        root.listFiles { file -> file.name.startsWith(".staging-") }.orEmpty().forEach { it.deleteRecursively() }
    }

    private fun directoryFor(
        family: String,
        source: InstalledSource,
    ): File = File(root, "${source.key}-${slug(family)}")

    internal companion object {
        private val NOT_SLUG = Regex("[^a-z0-9]+")

        fun slug(family: String): String = family.lowercase().replace(NOT_SLUG, "-").trim('-')
    }
}
