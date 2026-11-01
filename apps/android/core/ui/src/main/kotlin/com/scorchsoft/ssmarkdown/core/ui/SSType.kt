package com.scorchsoft.ssmarkdown.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The two families the chrome is set in: [sans] for every label and
 * [mono] for source text shown inside chrome (the Code pane stand-in,
 * diffs, the JSON tab).
 *
 * core/ui owns no font files. The app bundles Inter and JetBrains Mono in
 * `feature/fonts` and hands them in through [SSMarkdownTheme] (UX2-12), so
 * the design system never depends on a feature module. [System] is what
 * renders when nothing is handed in: component tests and previews.
 */
@Immutable
class SSFonts(
    val sans: FontFamily,
    val mono: FontFamily,
) {
    companion object {
        /** The platform's sans and monospace: the fallback when the bundled files are missing. */
        val System: SSFonts = SSFonts(FontFamily.Default, FontFamily.Monospace)
    }
}

/**
 * The six styles of the ladder, built once per [SSFonts]. [SSType] reads
 * the one [SSMarkdownTheme] provides.
 */
@Immutable
class SSTypeScale(
    fonts: SSFonts,
) {
    private val sans = fonts.sans

    val caption = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
    val meta = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val label = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
    val body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
    val display =
        TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.01).em,
        )
    val code =
        TextStyle(fontFamily = fonts.mono, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 20.sp)
}

/** The scale [SSMarkdownTheme] provides; the system families until a theme says otherwise. */
val LocalSSTypeScale = staticCompositionLocalOf { SSTypeScale(SSFonts.System) }

/**
 * The UI type ladder: 11 / 12 / 13 / 15 / 18 / 26 sp (docs/quality-bar.md
 * §3). Six sizes and no more; weight varies, size does not.
 *
 * The family comes from the theme ([SSFonts]): Inter for the chrome and
 * JetBrains Mono for code, as the design mockups set them.
 */
object SSType {
    /** 11 sp: line numbers, badges. */
    val caption: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalSSTypeScale.current.caption

    /** 12 sp: the status line under the title. */
    val meta: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalSSTypeScale.current.meta

    /** 13 sp: segments, pills and buttons. */
    val label: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalSSTypeScale.current.label

    /** 15 sp: body copy, menu rows. */
    val body: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalSSTypeScale.current.body

    /** 15 sp semibold: the document title in the top bar. */
    val bodyStrong: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalSSTypeScale.current.bodyStrong

    /** 18 sp: sheet and dialog titles. */
    val title: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalSSTypeScale.current.title

    /** 26 sp: the one large heading a screen may have. */
    val display: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalSSTypeScale.current.display

    /** Source text: 12.5 sp at a 1.6 line height. */
    val code: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalSSTypeScale.current.code
}
