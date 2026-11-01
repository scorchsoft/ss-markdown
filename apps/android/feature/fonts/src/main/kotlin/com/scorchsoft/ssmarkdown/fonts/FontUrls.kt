package com.scorchsoft.ssmarkdown.fonts

import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFonts
import java.io.File

/**
 * How the preview WebView reaches font files. The defaults follow
 * `androidx.webkit.WebViewAssetLoader`'s conventions on its default
 * domain: an `AssetsPathHandler` at `/assets/` and an
 * `InternalStoragePathHandler` at `/fonts/` over the [installedRoot]
 * directory. `feature/preview` registers those handlers; if it chooses
 * other paths, it passes its own bases here.
 *
 * @property bundledBase URL prefix for files under [BundledFonts.ASSET_DIR].
 * @property installedBase URL prefix mapped onto [installedRoot].
 * @property installedRoot the font store's root directory.
 */
data class FontUrls(
    val installedRoot: File,
    val bundledBase: String = "https://appassets.androidplatform.net/assets/${BundledFonts.ASSET_DIR}/",
    val installedBase: String = "https://appassets.androidplatform.net/fonts/",
) {
    /** The URL of bundled [file]. */
    fun bundled(file: String): String = bundledBase + file

    /** The URL of an installed [file]; null when it is not under [installedRoot]. */
    fun installed(file: File): String? {
        val relative = file.canonicalFile.relativeToOrNull(installedRoot.canonicalFile) ?: return null
        return if (relative.path.startsWith("..")) null else installedBase + relative.invariantSeparatorsPath
    }
}
