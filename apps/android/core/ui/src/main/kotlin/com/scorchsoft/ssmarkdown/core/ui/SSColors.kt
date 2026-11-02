package com.scorchsoft.ssmarkdown.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colour roles (docs/quality-bar.md §3, "Colour"). Components read
 * these through [SS.colors], never raw hexes, so a screen is theme-correct
 * by construction.
 *
 * Surfaces are neutral. Scorchsoft red is [needsYou] and means exactly
 * that: unsaved, Save, Save all, errors. It is never decoration, so there
 * is deliberately no "primary" or "accent" role to reach for instead.
 */
@Immutable
data class SSColors(
    val isDark: Boolean,
    /** The page behind the text. */
    val bg: Color,
    /** The top and bottom bars, sheets and menus. */
    val chrome: Color,
    /** Hairlines between chrome and page. Decorative: never the only edge of a control. */
    val border: Color,
    /**
     * A text field's resting outline: at least 3:1 on the page, the chrome
     * and the segment track (WCAG 1.4.11, UX2-10). [border] is 1.26:1 and
     * only separates surfaces.
     */
    val inputOutline: Color,
    /** Text and everything else that needs emphasis: syntax markers, toggles, the caret. */
    val ink: Color,
    /** Secondary text: the status line, unselected segments, the clean tick. */
    val inkMuted: Color,
    /** Line numbers and placeholder text; passes AA on the page. */
    val gutter: Color,
    /** Unsaved dot, Save pill, Save all and errors. */
    val needsYou: Color,
    /**
     * [needsYou] as text or a small icon: "Couldn't save", an invalid
     * pattern, an error glyph. In light mode it is [needsYou] itself; in dark
     * mode the pill red is only 3.2:1 on the chrome, so text gets a lighter
     * red that passes AA (UX-4). Never a fill: white on it fails.
     */
    val needsYouText: Color,
    /**
     * The tint behind an error callout (a failed save): [needsYouText] on it
     * passes AA in both themes, so the error reads as an error without
     * relying on the icon alone (UX2-6).
     */
    val needsYouBg: Color,
    /** Text on a [needsYou] fill. */
    val onNeedsYou: Color,
    /** Recovery, notes and alerts: [info] text on [infoBg]. */
    val info: Color,
    val infoBg: Color,
    /** Changed in another app, Big-file mode, contrast warnings: [caution] text on [cautionBg]. */
    val caution: Color,
    val cautionBg: Color,
    /** The track behind a segmented control, and its selected segment. */
    val segmentTrack: Color,
    val segmentSelected: Color,
)

val SSLightColors =
    SSColors(
        isDark = false,
        bg = Color(0xFFFFFFFF),
        chrome = Color(0xFFFAFAF9),
        border = Color(0xFFE6E5E2),
        inputOutline = Color(0xFF828288),
        ink = Color(0xFF1C1C1E),
        inkMuted = Color(0xFF636369),
        gutter = Color(0xFF6E6E73),
        needsYou = Color(0xFFB3191B),
        needsYouText = Color(0xFFB3191B),
        needsYouBg = Color(0xFFFCEBEA),
        onNeedsYou = Color(0xFFFFFFFF),
        info = Color(0xFF1D5DBF),
        infoBg = Color(0xFFEAF1FC),
        caution = Color(0xFF5C4300),
        cautionBg = Color(0xFFFFF4DA),
        segmentTrack = Color(0xFFECEBE8),
        segmentSelected = Color(0xFFFFFFFF),
    )

// Dark values come from the signed-off design canvas. The spec's gutter
// (#6E6E73) is a light-theme value: on the dark page it is only 3.6:1, so
// the dark gutter is #8E8E96. The spec gives no dark caution pair; the one
// here keeps the amber hue and passes AA (see SSColorsContrastTest). The
// pill red #C9302F is a fill colour; as text it is 3.2:1, so error text
// uses needsYouText #FF6B66 (6.1:1 on the chrome).
val SSDarkColors =
    SSColors(
        isDark = true,
        bg = Color(0xFF161618),
        chrome = Color(0xFF1C1C1F),
        border = Color(0xFF2E2E33),
        inputOutline = Color(0xFF80808A),
        ink = Color(0xFFECECEF),
        inkMuted = Color(0xFFA3A3AB),
        gutter = Color(0xFF8E8E96),
        needsYou = Color(0xFFC9302F),
        needsYouText = Color(0xFFFF6B66),
        needsYouBg = Color(0xFF33191A),
        onNeedsYou = Color(0xFFFFFFFF),
        info = Color(0xFF82B4FF),
        infoBg = Color(0xFF1B2536),
        caution = Color(0xFFF0CD7A),
        cautionBg = Color(0xFF2B2312),
        segmentTrack = Color(0xFF26262A),
        segmentSelected = Color(0xFF3B3B41),
    )

val LocalSSColors = staticCompositionLocalOf { SSLightColors }
