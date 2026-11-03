package com.scorchsoft.ssmarkdown.editor

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import com.scorchsoft.ssmarkdown.core.ui.SSColors
import com.scorchsoft.ssmarkdown.core.ui.SSDarkColors
import com.scorchsoft.ssmarkdown.core.ui.SSLightColors
import com.scorchsoft.ssmarkdown.theme.SyntaxColors
import com.scorchsoft.ssmarkdown.theme.ThemeColor
import com.scorchsoft.ssmarkdown.theme.contrast.Contrast

/**
 * The code view's colours as ARGB ints, built from the `core:ui` tokens
 * ([fromColors]) so the Code pane follows the design system without
 * pulling Compose into every draw call.
 *
 * The colour rule (ux-spec): emphasis is **ink** (block markers, the
 * caret, handles, headings), secondary syntax is **ink muted** (inline
 * markers, URLs, fences), links are the info blue, and text selection uses
 * the system selection colour. Red never appears here: it means "needs you".
 */
data class EditorTheme(
    /** The page. */
    val background: Int,
    /** Plain text and headings. */
    val text: Int,
    /** The line-number gutter. */
    val gutterBackground: Int,
    /** Line numbers. */
    val gutterText: Int,
    /** The caret and the selection handles. */
    val caret: Int,
    /** The underline under the keyboard's composing word. */
    val composingUnderline: Int,
    /** Selected text's background. */
    val selection: Int,
    /** Block markers that carry structure: `#`, list bullets, `>`, task boxes, table pipes. */
    val blockMarker: Int,
    /** Inline markers and secondary syntax: `**`, backticks, brackets, URLs, fences, HTML. */
    val inlineMarker: Int,
    /** Link text and autolinks. */
    val link: Int,
    /** Inline code's background chip. */
    val codeSpanBackground: Int,
    /** A fenced or indented code block's full-width band. */
    val codeBlockBackground: Int,
    /** Text inside code. */
    val codeText: Int,
    /** Every find match's fill: the Highlight token, used for find matches only (quality-bar §3). */
    val findMatch: Int = LIGHT_FIND_MATCH,
    /** The current find match's stronger fill. */
    val findCurrent: Int = LIGHT_FIND_CURRENT,
    /** The outline around the current find match: ink, so it reads without colour too. */
    val findCurrentOutline: Int = text,
    /** Highlighted code: comments. The nine `code*` colours are the theme's [SyntaxColors]; see [withSyntax]. */
    val codeComment: Int = argb(SyntaxColors.LIGHT.comment),
    /** Highlighted code: keywords, built-in types and literals. */
    val codeKeyword: Int = argb(SyntaxColors.LIGHT.keyword),
    /** Highlighted code: strings, regular expressions and diff additions. */
    val codeString: Int = argb(SyntaxColors.LIGHT.string),
    /** Highlighted code: numbers and symbols. */
    val codeNumber: Int = argb(SyntaxColors.LIGHT.number),
    /** Highlighted code: function and class names, section headers. */
    val codeTitle: Int = argb(SyntaxColors.LIGHT.title),
    /** Highlighted code: attributes, keys and variables. */
    val codeAttr: Int = argb(SyntaxColors.LIGHT.attr),
    /** Highlighted code: built-ins and markup tags. */
    val codeBuiltIn: Int = argb(SyntaxColors.LIGHT.builtIn),
    /** Highlighted code: annotations, decorators and preprocessor lines. */
    val codeMeta: Int = argb(SyntaxColors.LIGHT.meta),
    /** Highlighted code: deleted diff lines. */
    val codeDeletion: Int = argb(SyntaxColors.LIGHT.deletion),
) {
    /**
     * This theme with [syntax]'s code colours: the Preview theme's, so a
     * fenced block looks the same in both views. A colour that would read
     * below [MIN_CODE_CONTRAST] on this theme's code band (a custom theme
     * tuned for a different code background) falls back to the default for
     * the band's lightness, and that, should it fail too, to [codeText].
     */
    fun withSyntax(syntax: SyntaxColors): EditorTheme {
        val band = ThemeColor.of(codeBlockBackground)
        val defaults = if (Contrast.relativeLuminance(band) < HALF_LUMINANCE) SyntaxColors.DARK else SyntaxColors.LIGHT

        fun pick(
            chosen: ThemeColor,
            fallback: ThemeColor,
        ): Int =
            when {
                Contrast.ratio(chosen, band) >= MIN_CODE_CONTRAST -> argb(chosen)
                Contrast.ratio(fallback, band) >= MIN_CODE_CONTRAST -> argb(fallback)
                else -> codeText
            }
        return copy(
            codeComment = pick(syntax.comment, defaults.comment),
            codeKeyword = pick(syntax.keyword, defaults.keyword),
            codeString = pick(syntax.string, defaults.string),
            codeNumber = pick(syntax.number, defaults.number),
            codeTitle = pick(syntax.title, defaults.title),
            codeAttr = pick(syntax.attr, defaults.attr),
            codeBuiltIn = pick(syntax.builtIn, defaults.builtIn),
            codeMeta = pick(syntax.meta, defaults.meta),
            codeDeletion = pick(syntax.deletion, defaults.deletion),
        )
    }

    /** Built-in themes. */
    companion object {
        /** WCAG AA for normal text: every code colour on the code band (quality-bar §3). */
        const val MIN_CODE_CONTRAST: Double = 4.5
        private const val HALF_LUMINANCE = 0.5
        private const val OPAQUE = 0xFF shl 24

        private fun argb(color: ThemeColor): Int = OPAQUE or color.rgb

        /** The light theme from the `core:ui` light tokens. */
        val LIGHT: EditorTheme = fromColors(SSLightColors)

        /** The dark theme from the `core:ui` dark tokens. */
        val DARK: EditorTheme = fromColors(SSDarkColors)

        /**
         * Maps design-system roles onto editor roles; [selection] defaults to a
         * translucent info blue, and the code colours to the default
         * [SyntaxColors] for the appearance ([withSyntax] applies a theme's).
         */
        fun fromColors(
            colors: SSColors,
            selection: Int = (colors.info.toArgb() and RGB_MASK) or SELECTION_ALPHA,
            syntax: SyntaxColors = if (colors.isDark) SyntaxColors.DARK else SyntaxColors.LIGHT,
        ): EditorTheme =
            EditorTheme(
                background = colors.bg.toArgb(),
                text = colors.ink.toArgb(),
                gutterBackground = colors.chrome.toArgb(),
                gutterText = colors.gutter.toArgb(),
                caret = colors.ink.toArgb(),
                composingUnderline = colors.inkMuted.toArgb(),
                selection = selection,
                blockMarker = colors.ink.toArgb(),
                inlineMarker = colors.inkMuted.toArgb(),
                link = colors.info.toArgb(),
                codeSpanBackground = colors.segmentTrack.toArgb(),
                codeBlockBackground = colors.chrome.toArgb(),
                codeText = colors.ink.toArgb(),
                findMatch = if (colors.isDark) DARK_FIND_MATCH else LIGHT_FIND_MATCH,
                findCurrent = if (colors.isDark) DARK_FIND_CURRENT else LIGHT_FIND_CURRENT,
                findCurrentOutline = colors.ink.toArgb(),
            ).withSyntax(syntax)

        /**
         * The theme for [context]'s light/dark mode, with the system's
         * text-selection colour (`android:textColorHighlight`) when the
         * context's theme defines one.
         */
        fun forContext(context: Context): EditorTheme {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val base = if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
            val value = TypedValue()
            val resolved = context.theme.resolveAttribute(android.R.attr.textColorHighlight, value, true)
            val isColor = value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
            return if (resolved && isColor) base.copy(selection = value.data) else base
        }

        // quality-bar §3 "Highlight": #FFF1B8, current #F5C542 with an ink outline. The spec
        // gives light values only; the dark pair keeps the hue at a depth where light ink
        // on it still passes AA (FindHighlightsTest checks both).
        private const val LIGHT_FIND_MATCH = 0xFFFFF1B8.toInt()
        private const val LIGHT_FIND_CURRENT = 0xFFF5C542.toInt()
        private const val DARK_FIND_MATCH = 0xFF45390F.toInt()
        private const val DARK_FIND_CURRENT = 0xFF6E5410.toInt()

        private const val RGB_MASK = 0x00FFFFFF
        private const val SELECTION_ALPHA = 0x55 shl 24
    }
}
