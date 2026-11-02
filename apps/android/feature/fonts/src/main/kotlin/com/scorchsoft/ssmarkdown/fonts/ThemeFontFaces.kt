package com.scorchsoft.ssmarkdown.fonts

import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFonts
import com.scorchsoft.ssmarkdown.fonts.catalogue.GoogleFontsCatalogue
import com.scorchsoft.ssmarkdown.theme.css.FontFace
import com.scorchsoft.ssmarkdown.theme.css.FontFaceSource
import com.scorchsoft.ssmarkdown.theme.css.FontFormat
import com.scorchsoft.ssmarkdown.theme.css.GenericFamily

/**
 * Answers `core:theme`'s [FontFaceSource] from what is on this phone, so
 * `ThemeCss` can emit `@font-face` rules. A missing family yields no faces
 * and its stack falls through to the generic family (the bundled fallback
 * is chosen by category, so serif stays serif).
 */
internal class ThemeFontFaces(
    private val resolve: (String) -> FontResolution,
    private val catalogue: () -> GoogleFontsCatalogue,
    private val urls: FontUrls,
) : FontFaceSource {
    override fun faces(family: String): List<FontFace> =
        when (val resolution = resolve(family)) {
            is FontResolution.Bundled -> {
                resolution.font.files.map {
                    FontFace(resolution.family, urls.bundled(it.file), it.italic, it.weights, FontFormat.TRUETYPE)
                }
            }

            is FontResolution.Installed -> {
                resolution.font.files.mapNotNull { file ->
                    val format = if (file.file.extension == "otf") FontFormat.OPENTYPE else FontFormat.TRUETYPE
                    urls.installed(file.file)?.let { url ->
                        FontFace(
                            resolution.family,
                            url,
                            file.style.italic,
                            file.style.weight..file.style.weight,
                            format,
                        )
                    }
                }
            }

            is FontResolution.Missing -> {
                emptyList()
            }
        }

    override fun generic(family: String): GenericFamily =
        (BundledFonts.byFamily(family)?.category ?: catalogue().find(family)?.category)?.generic
            ?: GenericFamily.SANS_SERIF
}
