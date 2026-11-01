package com.scorchsoft.ssmarkdown.editor

import android.content.Context
import android.content.res.Configuration

/**
 * The code view's colours as ARGB ints. The values mirror `SSColors` in
 * `core:ui`, which is Compose-typed; this View cannot take Compose colours
 * without pulling Compose into every draw call, so the real view will be
 * handed a palette built from the active theme. Until then, light and dark
 * follow the system.
 */
data class EditorPalette(
    val background: Int,
    val text: Int,
    val gutterBackground: Int,
    val gutterText: Int,
    val caret: Int,
    val composingUnderline: Int,
) {
    /** The two built-in palettes. */
    companion object {
        /** Light: SS bg, ink, chrome, gutter, info. */
        val LIGHT: EditorPalette =
            EditorPalette(
                background = 0xFFFFFFFF.toInt(),
                text = 0xFF1C1C1E.toInt(),
                gutterBackground = 0xFFFAFAF9.toInt(),
                gutterText = 0xFF6E6E73.toInt(),
                caret = 0xFF1D5DBF.toInt(),
                composingUnderline = 0xFF636369.toInt(),
            )

        /** Dark: the same roles from the dark token set. */
        val DARK: EditorPalette =
            EditorPalette(
                background = 0xFF161618.toInt(),
                text = 0xFFECECEF.toInt(),
                gutterBackground = 0xFF1C1C1F.toInt(),
                gutterText = 0xFF8E8E96.toInt(),
                caret = 0xFF82B4FF.toInt(),
                composingUnderline = 0xFFA3A3AB.toInt(),
            )

        /** The palette for [context]'s current light/dark mode. */
        fun forContext(context: Context): EditorPalette {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
        }
    }
}
