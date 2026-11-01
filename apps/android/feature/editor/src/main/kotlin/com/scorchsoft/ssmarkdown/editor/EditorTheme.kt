package com.scorchsoft.ssmarkdown.editor

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import com.scorchsoft.ssmarkdown.core.ui.SSColors
import com.scorchsoft.ssmarkdown.core.ui.SSDarkColors
import com.scorchsoft.ssmarkdown.core.ui.SSLightColors

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
) {
    /** Built-in themes. */
    companion object {
        /** The light theme from the `core:ui` light tokens. */
        val LIGHT: EditorTheme = fromColors(SSLightColors)

        /** The dark theme from the `core:ui` dark tokens. */
        val DARK: EditorTheme = fromColors(SSDarkColors)

        /** Maps design-system roles onto editor roles; [selection] defaults to a translucent info blue. */
        fun fromColors(
            colors: SSColors,
            selection: Int = (colors.info.toArgb() and RGB_MASK) or SELECTION_ALPHA,
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
            )

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

        private const val RGB_MASK = 0x00FFFFFF
        private const val SELECTION_ALPHA = 0x55 shl 24
    }
}
