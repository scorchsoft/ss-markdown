package com.scorchsoft.ssmarkdown.themes

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.theme.Theme
import com.scorchsoft.ssmarkdown.theme.ThemeVariant
import com.scorchsoft.ssmarkdown.theme.codec.ThemeCodec

/**
 * What the preview page needs to draw a theme: its JSON (the ux-spec
 * shape the page reads), which variant, and the page colour to paint
 * before the first frame so a dark theme never flashes white.
 *
 * @property background an opaque ARGB colour.
 */
@Immutable
data class PreviewLook(
    val themeJson: String,
    val dark: Boolean,
    val background: Int,
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
    }
}
