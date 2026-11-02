package com.scorchsoft.ssmarkdown.fonts

import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFonts
import com.scorchsoft.ssmarkdown.fonts.catalogue.CatalogueEntry
import com.scorchsoft.ssmarkdown.fonts.catalogue.GoogleFontsCatalogue
import com.scorchsoft.ssmarkdown.fonts.download.DownloadResult
import com.scorchsoft.ssmarkdown.fonts.download.FontDownloader
import com.scorchsoft.ssmarkdown.fonts.importing.FontImporter
import com.scorchsoft.ssmarkdown.fonts.importing.ImportResult
import com.scorchsoft.ssmarkdown.fonts.store.FontStore
import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.ThemeVariant
import com.scorchsoft.ssmarkdown.theme.css.ThemeCss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.UnknownHostException

class FontRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val http = FakeHttp()
    private val store by lazy { FontStore(temp.newFolder("fonts")) }
    private var bundledOnDisk = true
    private var catalogueLoads = 0

    private val repository by lazy {
        val dispatcher = Dispatchers.Unconfined
        FontRepository(
            store = store,
            downloader = FontDownloader(http, store, Clock { 7 }, dispatcher),
            importer = FontImporter(store, Clock { 7 }, dispatcher),
            catalogue = {
                catalogueLoads++
                GoogleFontsCatalogue(
                    listOf(
                        CatalogueEntry("Inter", FontCategory.SANS, listOf(FontStyleKey(400, false)), 190),
                        CatalogueEntry("Merriweather", FontCategory.SERIF, listOf(FontStyleKey(400, false)), 120),
                    ),
                )
            },
            bundledPresent = { bundledOnDisk },
            urls = FontUrls(installedRoot = store.root),
        )
    }

    @Test
    fun `resolution covers bundled, downloadable and unknown, with category-aware fallbacks`() {
        assertEquals(FontResolution.Bundled(BundledFonts.INTER), repository.resolve("inter"))
        assertEquals(
            BundledFonts.SOURCE_SERIF_4,
            (repository.resolve("Merriweather") as FontResolution.Downloadable).fallback,
        )
        assertEquals(FontResolution.Unknown("Nope", BundledFonts.INTER), repository.resolve(" Nope "))
        repository.resolve("x")
        assertEquals("the catalogue is parsed once", 1, catalogueLoads)
    }

    @Test
    fun `a build without the font binaries treats bundled families as missing`() {
        bundledOnDisk = false
        assertTrue(repository.resolve("Inter") is FontResolution.Downloadable)
    }

    @Test
    fun `download routes through the resolution`() =
        runTest {
            assertEquals(DownloadResult.AlreadyBundled("Inter"), repository.download("Inter"))
            http.failEverything(UnknownHostException())
            val offline = repository.download("Merriweather") as DownloadResult.Failure
            assertEquals(
                "Couldn't download Merriweather — you're offline. Using Source Serif 4 until then.",
                offline.message(),
            )
            assertEquals(DownloadResult.NotFound("Nope", "Inter"), repository.download("Nope"))
            assertEquals(1, http.requests.size)
        }

    @Test
    fun `an imported font resolves as installed and feeds the theme CSS`() =
        runTest {
            val imported = repository.import("face.ttf") { TestFonts.ttf("My Face", 400).inputStream() }
            assertTrue(imported is ImportResult.Imported)
            assertTrue(repository.resolve("My Face") is FontResolution.Installed)
            assertTrue(repository.verify("My Face"))
            assertTrue(repository.storageBytes() > 0)

            val theme = BuiltInThemes.CLEAN.copy(fonts = BuiltInThemes.CLEAN.fonts.copy(heading = "My Face"))
            val css = ThemeCss.stylesheet(theme, ThemeVariant.LIGHT, repository.faceSource)
            assertTrue(
                css,
                css.contains("url(\"https://appassets.androidplatform.net/assets/fonts/bundled/inter.ttf\")"),
            )
            assertTrue(css, css.contains("font-weight: 100 900;"))
            assertTrue(
                css,
                css.contains("url(\"https://appassets.androidplatform.net/fonts/imported-my-face/400.ttf\")"),
            )
            assertTrue(css, css.contains("--ssm-font-code: \"JetBrains Mono\", monospace;"))

            assertTrue(repository.delete("My Face"))
            assertTrue(repository.resolve("My Face") is FontResolution.Unknown)
        }

    @Test
    fun `serif families fall back to a serif generic in CSS`() {
        assertEquals("serif", repository.faceSource.generic("Merriweather").css)
        assertEquals("serif", repository.faceSource.generic("Literata").css)
        assertEquals("sans-serif", repository.faceSource.generic("Unknown Face").css)
    }
}
