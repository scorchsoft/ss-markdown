package com.scorchsoft.ssmarkdown.fonts.store

import com.scorchsoft.ssmarkdown.fonts.FontStyleKey
import java.io.File

/** Where an installed family came from. */
enum class InstalledSource(
    /** The spelling in the index file and the directory prefix. */
    val key: String,
) {
    /** Downloaded from Google Fonts. */
    GOOGLE("google"),

    /** Imported by the person from a `.ttf` or `.otf`. */
    IMPORTED("imported"),
}

/**
 * One file of an installed family.
 *
 * @property file where it is on disk.
 * @property style its weight and slope.
 * @property bytes its size when it was written.
 * @property sha256 its checksum when it was written, for [FontStore.verify].
 */
data class InstalledFile(
    val file: File,
    val style: FontStyleKey,
    val bytes: Long,
    val sha256: String,
)

/**
 * A family on this phone that did not ship in the APK.
 *
 * @property family the family name themes refer to.
 * @property source downloaded or imported.
 * @property installedAtMillis when it was written (for Storage's list).
 * @property files its files.
 */
data class InstalledFont(
    val family: String,
    val source: InstalledSource,
    val installedAtMillis: Long,
    val files: List<InstalledFile>,
) {
    /** Total size on disk, for the picker row and the Storage screen. */
    val bytes: Long get() = files.sumOf { it.bytes }
}
