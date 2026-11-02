package com.scorchsoft.ssmarkdown.themes

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.preview.bridge.ThemeFonts
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.ThemeVariant
import com.scorchsoft.ssmarkdown.theme.codec.ThemeCodec
import com.scorchsoft.ssmarkdown.theme.css.FontFaceSource
import com.scorchsoft.ssmarkdown.theme.css.GenericFamily
import com.scorchsoft.ssmarkdown.theme.css.ThemeCss

/**
 * What the preview page needs to draw a theme: its JSON (the ux-spec
 * shape the page reads), which variant, and the page colour to paint
 * before the first frame so a dark theme never flashes white.
 *
 * @property background an opaque ARGB colour.
 * @property fonts the theme's font files and stacks as this phone has
 *   them; null until they are resolved (the page uses system fonts meanwhile).
 */
@Immutable
data class PreviewLook(
    val themeJson: String,
    val dark: Boolean,
    val background: Int,
    val fonts: ThemeFonts? = null,
) {
    /** Building looks. */
    companion object {
        private const val OPAQUE = 0xFF000000.toInt()

        /** [theme] in its dark or light variant. */
        fun of(
            theme: Theme,
            dark: Boolean,
        ): PreviewLook {
            val variant = if (dark) ThemeVariant.DARK else ThemeVariant.LIGHT
            return PreviewLook(ThemeCodec.encode(theme), dark, OPAQUE or theme.palette(variant).bg.rgb)
        }

        /**
         * [theme]'s `@font-face` rules and stacks from [faces] (feature/fonts'
         * repository), through core:theme's `ThemeCss`, so the preview draws
         * the theme's own fonts. May read the font store: call off the main thread.
         */
        fun fontsOf(
            theme: Theme,
            faces: FontFaceSource,
        ): ThemeFonts {
            val fonts = theme.fonts
            return ThemeFonts(
                faces = ThemeCss.fontFaces(theme, faces),
                body = ThemeCss.stack(fonts.body, faces.generic(fonts.body)),
                heading = ThemeCss.stack(fonts.heading, faces.generic(fonts.heading)),
                code = ThemeCss.stack(fonts.code, GenericFamily.MONOSPACE),
            )
        }
    }
}
