package com.scorchsoft.ssmarkdown.ui

import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.scorchsoft.ssmarkdown.core.ui.SSFonts
import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFont
import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFonts
import java.io.IOException

/**
 * The app's own faces (docs/ux-spec.md, "Type"; UX2-12): Inter for the
 * chrome and JetBrains Mono for code, read from the files `feature/fonts`
 * already bundles for the Preview themes, so they cost no extra bytes.
 *
 * core/ui owns no fonts and must not depend on a feature, so the app
 * builds the families here and hands them to `SSMarkdownTheme`, and the
 * code face to the Code pane. Both are variable fonts: one file serves
 * every weight the type ladder uses.
 *
 * Nothing here may throw on the launch path. A missing file (a stripped
 * build, a broken install) falls back to the system families rather than
 * failing at the first text layout.
 */
object ChromeFonts {
    /** The weights the ladder uses (Normal, Medium, SemiBold, Bold), so no weight is synthesised. */
    private val LadderWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

    /** The chrome's families, or [SSFonts.System] for any that is missing. */
    fun families(assets: AssetManager): SSFonts {
        val present = bundledFiles(assets)
        return SSFonts(
            sans = family(assets, BundledFonts.INTER, present) ?: FontFamily.Default,
            mono = family(assets, BundledFonts.JETBRAINS_MONO, present) ?: FontFamily.Monospace,
        )
    }

    /**
     * JetBrains Mono at its regular weight for the Code pane's paint, or
     * null (the view keeps the system monospace). Read once per process.
     */
    fun codeTypeface(assets: AssetManager): Typeface? = code ?: loadCode(assets).also { code = it }

    @Volatile private var code: Typeface? = null

    private fun loadCode(assets: AssetManager): Typeface? {
        val upright =
            BundledFonts.JETBRAINS_MONO.files
                .first { !it.italic }
                .file
        if (upright !in bundledFiles(assets)) return null
        return try {
            Typeface
                .Builder(assets, "${BundledFonts.ASSET_DIR}/$upright")
                .setFontVariationSettings("'wght' $REGULAR")
                .build()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun family(
        assets: AssetManager,
        font: BundledFont,
        present: Set<String>,
    ): FontFamily? {
        val files = font.files.filter { it.file in present }
        if (files.none { !it.italic }) return null
        val faces =
            files.flatMap { file ->
                val style = if (file.italic) FontStyle.Italic else FontStyle.Normal
                LadderWeights.map { weight ->
                    Font("${BundledFonts.ASSET_DIR}/${file.file}", assets, weight = weight, style = style)
                }
            }
        return FontFamily(faces)
    }

    private fun bundledFiles(assets: AssetManager): Set<String> =
        try {
            assets.list(BundledFonts.ASSET_DIR).orEmpty().toSet()
        } catch (_: IOException) {
            emptySet()
        }

    private const val REGULAR = 400
}
