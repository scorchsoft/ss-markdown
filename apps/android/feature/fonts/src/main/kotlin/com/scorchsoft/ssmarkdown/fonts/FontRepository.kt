package com.scorchsoft.ssmarkdown.fonts

import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFile
import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFonts
import com.scorchsoft.ssmarkdown.fonts.catalogue.GoogleFontsCatalogue
import com.scorchsoft.ssmarkdown.fonts.download.DownloadProgress
import com.scorchsoft.ssmarkdown.fonts.download.DownloadResult
import com.scorchsoft.ssmarkdown.fonts.download.FontDownloader
import com.scorchsoft.ssmarkdown.fonts.importing.FontImporter
import com.scorchsoft.ssmarkdown.fonts.importing.ImportResult
import com.scorchsoft.ssmarkdown.fonts.store.FontStore
import com.scorchsoft.ssmarkdown.fonts.store.InstalledFont
import com.scorchsoft.ssmarkdown.theme.css.FontFaceSource
import java.io.InputStream

/**
 * The one door to fonts for the rest of the app: what a family name
 * resolves to, the picker's catalogue, downloads, imports, deletion and
 * storage use. Every outcome is a sealed value; nothing here throws for
 * an expected failure.
 *
 * Construct with [AndroidFonts.repository] in the app; tests build it
 * from fakes.
 *
 * @param store installed (downloaded and imported) families.
 * @param downloader fetches Google Fonts families.
 * @param importer imports `.ttf` / `.otf` files.
 * @param catalogue the bundled Google Fonts list, loaded on first use.
 * @param bundledPresent whether a bundled file is actually in the APK.
 *   The binaries are produced by `tools/fetch-fonts.sh`; a build without
 *   them still runs and treats those families as missing.
 * @param urls how the preview reaches font files.
 */
class FontRepository(
    private val store: FontStore,
    private val downloader: FontDownloader,
    private val importer: FontImporter,
    catalogue: () -> GoogleFontsCatalogue,
    private val bundledPresent: (BundledFile) -> Boolean,
    urls: FontUrls,
) {
    private val catalogueOnce by lazy(catalogue)

    /** The Google Fonts catalogue (parsed once, on first use). */
    val catalogue: GoogleFontsCatalogue get() = catalogueOnce

    /** Feeds `ThemeCss` the `@font-face` rules for what is on this phone. */
    val faceSource: FontFaceSource = ThemeFontFaces(::resolve, { catalogueOnce }, urls)

    /** What [family] resolves to right now. Cheap enough for list rows; does no network. */
    fun resolve(family: String): FontResolution {
        val bundled = BundledFonts.byFamily(family)?.takeIf { font -> font.files.all(bundledPresent) }
        val installed = if (bundled == null) store.find(family) else null
        val entry = catalogueOnce.find(family)
        val fallback = BundledFonts.fallbackFor(bundled?.category ?: entry?.category)
        return when {
            bundled != null -> FontResolution.Bundled(bundled)
            installed != null -> FontResolution.Installed(installed)
            entry != null -> FontResolution.Downloadable(entry, fallback)
            else -> FontResolution.Unknown(family.trim(), fallback)
        }
    }

    /** Every downloaded and imported family, for Settings → Storage. */
    fun installed(): List<InstalledFont> = store.installed()

    /** Bytes used by downloaded and imported fonts ("Downloaded fonts" on the Storage screen). */
    fun storageBytes(): Long = store.totalBytes()

    /**
     * Downloads [family] from Google Fonts. Already-available families
     * return at once without touching the network: an installed one as
     * [DownloadResult.Done], a bundled one as [DownloadResult.AlreadyBundled].
     */
    suspend fun download(
        family: String,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult =
        when (val resolution = resolve(family)) {
            is FontResolution.Installed -> {
                DownloadResult.Done(resolution.font)
            }

            is FontResolution.Bundled -> {
                DownloadResult.AlreadyBundled(resolution.family)
            }

            is FontResolution.Downloadable -> {
                downloader.download(
                    resolution.entry,
                    resolution.fallback.family,
                    onProgress,
                )
            }

            is FontResolution.Unknown -> {
                DownloadResult.NotFound(resolution.family, resolution.fallback.family)
            }
        }

    /** Imports a font file; [fileName] is its display name, [open] streams its bytes. */
    suspend fun import(
        fileName: String,
        open: () -> InputStream,
    ): ImportResult = importer.import(fileName, open)

    /** Removes a downloaded or imported family. Bundled families can't be removed; returns false. */
    fun delete(family: String): Boolean = store.delete(family)

    /** True when every file of an installed [family] still matches its recorded checksum. */
    fun verify(family: String): Boolean = store.find(family)?.let { store.verify(it) } ?: false
}
