package com.scorchsoft.ssmarkdown.fonts

import android.content.Context
import android.content.res.AssetManager
import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFile
import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFonts
import com.scorchsoft.ssmarkdown.fonts.catalogue.GoogleFontsCatalogue
import com.scorchsoft.ssmarkdown.fonts.download.FontDownloader
import com.scorchsoft.ssmarkdown.fonts.download.UrlConnectionTransport
import com.scorchsoft.ssmarkdown.fonts.importing.FontImporter
import com.scorchsoft.ssmarkdown.fonts.store.FontStore
import java.io.File
import java.io.IOException

/**
 * Wires [FontRepository] to Android: assets for the bundled fonts and
 * catalogue, `filesDir/fonts` for installed ones (the same directory as
 * feature/files' `StorageLayout.fonts`), and the real network. The only
 * Android-typed file in the module besides [SafFontSource].
 */
object AndroidFonts {
    /** The installed-fonts directory under [Context.getFilesDir]. */
    const val DIRECTORY: String = "fonts"

    /** Builds the app's repository. Cheap: nothing is read until first use. */
    fun repository(context: Context): FontRepository {
        val app = context.applicationContext
        val assets = app.assets
        val store = FontStore(File(app.filesDir, DIRECTORY))
        return FontRepository(
            store = store,
            downloader = FontDownloader(UrlConnectionTransport(), store, Clock.SYSTEM),
            importer = FontImporter(store, Clock.SYSTEM),
            catalogue = { loadCatalogue(assets) },
            bundledPresent = bundledPresence(assets),
            urls = FontUrls(installedRoot = store.root),
        )
    }

    private fun loadCatalogue(assets: AssetManager): GoogleFontsCatalogue =
        try {
            assets.open(GoogleFontsCatalogue.ASSET).reader().use { GoogleFontsCatalogue.parse(it) }
        } catch (_: IOException) {
            GoogleFontsCatalogue(emptyList())
        }

    private fun bundledPresence(assets: AssetManager): (BundledFile) -> Boolean {
        val present by lazy {
            try {
                assets.list(BundledFonts.ASSET_DIR).orEmpty().toSet()
            } catch (_: IOException) {
                emptySet()
            }
        }
        return { file -> file.file in present }
    }
}
