package com.scorchsoft.ssmarkdown.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Provides [LocalSSColors] and a Material theme filled from the tokens, so
 * a stray Material component still lands in the palette. Dynamic colour is
 * off: red has a meaning here and wallpaper must not move it.
 */
@Composable
fun SSMarkdownTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val c = if (darkTheme) SSDarkColors else SSLightColors
    val scheme =
        (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
            // Ink, not red: the default "primary" colours toggles, sliders and
            // the caret, and those are ink by spec.
            primary = c.ink,
            onPrimary = c.bg,
            background = c.bg,
            onBackground = c.ink,
            surface = c.chrome,
            onSurface = c.ink,
            surfaceVariant = c.segmentTrack,
            onSurfaceVariant = c.inkMuted,
            outline = c.border,
            outlineVariant = c.border,
            error = c.needsYou,
            onError = c.onNeedsYou,
            surfaceTint = c.chrome,
        )
    CompositionLocalProvider(LocalSSColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography =
                Typography(
                    bodyLarge = SSType.body,
                    bodyMedium = SSType.label,
                    bodySmall = SSType.meta,
                    titleLarge = SSType.title,
                    titleMedium = SSType.bodyStrong,
                    labelLarge = SSType.label,
                    labelSmall = SSType.caption,
                    displaySmall = SSType.display,
                ),
            content = content,
        )
    }
}
