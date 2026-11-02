package com.scorchsoft.ssmarkdown.themes

import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFonts
import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.css.FontFace
import com.scorchsoft.ssmarkdown.theme.css.FontFaceSource
import com.scorchsoft.ssmarkdown.theme.css.FontFormat
import com.scorchsoft.ssmarkdown.theme.css.GenericFamily
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The built-in themes exactly as the app sends them to the page, recorded
 * in `feature/preview/web/test/vectors/themes.json` for the page's
 * Playwright theme tests (rubric #5 and #7). The page's own
 * `BUILT_IN_THEMES` only draws until the app's theme arrives, so measuring
 * contrast and line length on it would measure the wrong thing.
 *
 * Fonts are the bundled files, at the path the test harness serves them
 * from; the app serves the same files from its own origin. If this test
 * fails after a theme change, copy the JSON it prints into the vector file.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeVectorsTest {
    private val vectors = File("../feature/preview/web/test/vectors/themes.json")

    /**
     * The bundled fonts at the harness's `/assets/fonts/bundled/` path. A
     * family listed in [BundledFonts] whose files are not in the APK (IBM
     * Plex Sans and Lora are downloads, to keep the APK small) is drawn in
     * its category's bundled fallback ([BundledFonts.fallbackFor]), the face
     * the app shows while it is missing; Chromium's own sans would make the
     * measurement depend on the machine that runs it.
     */
    private object BundledOnly : FontFaceSource {
        override fun faces(family: String): List<FontFace> {
            val font = BundledFonts.byFamily(family) ?: return emptyList()
            val present = font.takeIf { f -> f.files.all { File(BUNDLED_DIR, it.file).exists() } }
            val files = present ?: BundledFonts.fallbackFor(font.category)
            return files.files.map {
                FontFace(family, "/assets/fonts/bundled/${it.file}", it.italic, it.weights, FontFormat.TRUETYPE)
            }
        }

        override fun generic(family: String): GenericFamily =
            BundledFonts.byFamily(family)?.category?.generic ?: GenericFamily.SANS_SERIF
    }

    private fun expected(): JSONObject {
        val themes = JSONArray()
        BuiltInThemes.ALL.forEach { theme ->
            val look = PreviewLook.of(theme, dark = false)
            val fonts = PreviewLook.fontsOf(theme, BundledOnly)
            themes.put(
                JSONObject()
                    .put("id", theme.id)
                    .put("theme", JSONObject(look.themeJson))
                    .put(
                        "fonts",
                        JSONObject()
                            .put("faces", fonts.faces)
                            .put("body", fonts.body)
                            .put("heading", fonts.heading)
                            .put("code", fonts.code),
                    ),
            )
        }
        return JSONObject().put("themes", themes)
    }

    private companion object {
        /** Where the bundled files are in the source tree. */
        val BUNDLED_DIR = File("../feature/fonts/src/main/assets/${BundledFonts.ASSET_DIR}")
    }

    @Test
    fun `the page's theme vectors are the built-in themes the app sends`() {
        val expected = expected().toString(2)
        val actual = if (vectors.exists()) JSONObject(vectors.readText()).toString(2) else ""
        assertEquals("Update ${vectors.path} with:\n$expected\n", expected, actual)
    }
}
