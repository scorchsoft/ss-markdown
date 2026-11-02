package com.scorchsoft.ssmarkdown.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Provides [LocalSSColors], [LocalSSTypeScale] and a Material theme filled
 * from the tokens, so a stray Material component still lands in the
 * palette. Dynamic colour is off: red has a meaning here and wallpaper
 * must not move it.
 *
 * @param fonts the chrome's families. The app passes the bundled Inter and
 *   JetBrains Mono; the default is the system's, for tests and previews.
 */
@Composable
fun SSMarkdownTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fonts: SSFonts = SSFonts.System,
    content: @Composable () -> Unit,
) {
    val type = remember(fonts) { SSTypeScale(fonts) }
    val c = if (darkTheme) SSDarkColors else SSLightColors
    val scheme = remember(c) { schemeFor(c) }
    CompositionLocalProvider(LocalSSColors provides c, LocalSSTypeScale provides type) {
        MaterialTheme(
            colorScheme = scheme,
            typography =
                Typography(
                    bodyLarge = type.body,
                    bodyMedium = type.label,
                    bodySmall = type.meta,
                    titleLarge = type.title,
                    titleMedium = type.bodyStrong,
                    titleSmall = type.label,
                    labelLarge = type.label,
                    labelMedium = type.caption,
                    labelSmall = type.caption,
                    headlineSmall = type.title,
                    displaySmall = type.display,
                ),
            content = content,
        )
    }
}

/** Material's colour scheme filled from [c], so a stray Material component lands in the palette. */
private fun schemeFor(c: SSColors): ColorScheme =
    (if (c.isDark) darkColorScheme() else lightColorScheme()).copy(
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
        // `outline` is a text field's resting border: it must reach 3:1 (UX2-10).
        outline = c.inputOutline,
        outlineVariant = c.border,
        // Every container role, mapped so no stock lavender leaks in (UX2-9):
        // progress tracks, chips and menus all read these.
        primaryContainer = c.segmentTrack,
        onPrimaryContainer = c.ink,
        secondary = c.inkMuted,
        onSecondary = c.bg,
        secondaryContainer = c.segmentTrack,
        onSecondaryContainer = c.ink,
        tertiary = c.inkMuted,
        onTertiary = c.bg,
        tertiaryContainer = c.segmentTrack,
        onTertiaryContainer = c.ink,
        inversePrimary = c.inkMuted,
        inverseSurface = c.ink,
        inverseOnSurface = c.bg,
        surfaceBright = c.chrome,
        surfaceDim = c.segmentTrack,
        surfaceContainerLowest = c.bg,
        surfaceContainerLow = c.chrome,
        surfaceContainer = c.chrome,
        surfaceContainerHigh = c.chrome,
        surfaceContainerHighest = c.segmentTrack,
        errorContainer = c.needsYouBg,
        onErrorContainer = c.needsYouText,
        scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
        // Material draws error text and icons with `error`, so it is the
        // text-safe red; in dark that red is light, so ink on it is the page colour.
        error = c.needsYouText,
        onError = if (c.isDark) c.bg else c.onNeedsYou,
        surfaceTint = c.chrome,
    )

/** Material's own scrim weight: dims the page behind a sheet without hiding it. */
private const val SCRIM_ALPHA = 0.32f
