package com.scorchsoft.ssmarkdown.theme.contrast

import com.scorchsoft.ssmarkdown.theme.ThemeColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast maths, exactly as the spec defines it
 * (https://www.w3.org/TR/WCAG22/#dfn-contrast-ratio), so a number shown
 * in the theme editor matches what any WCAG checker reports.
 */
public object Contrast {
    private const val CHANNEL_MAX = 255.0
    private const val LINEAR_THRESHOLD = 0.04045
    private const val LINEAR_DIVISOR = 12.92
    private const val GAMMA_OFFSET = 0.055
    private const val GAMMA_DIVISOR = 1.055
    private const val GAMMA = 2.4
    private const val RED_WEIGHT = 0.2126
    private const val GREEN_WEIGHT = 0.7152
    private const val BLUE_WEIGHT = 0.0722
    private const val FLARE = 0.05

    /** Relative luminance, 0 (black) to 1 (white). */
    public fun relativeLuminance(color: ThemeColor): Double =
        RED_WEIGHT * linear(color.red) + GREEN_WEIGHT * linear(color.green) + BLUE_WEIGHT * linear(color.blue)

    /** The contrast ratio between two colours, 1.0 to 21.0; order does not matter. */
    public fun ratio(
        first: ThemeColor,
        second: ThemeColor,
    ): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (max(a, b) + FLARE) / (min(a, b) + FLARE)
    }

    private fun linear(channel: Int): Double {
        val c = channel / CHANNEL_MAX
        return if (c <= LINEAR_THRESHOLD) c / LINEAR_DIVISOR else ((c + GAMMA_OFFSET) / GAMMA_DIVISOR).pow(GAMMA)
    }
}

/**
 * A WCAG conformance level and the ratios it demands (success criteria
 * 1.4.3 and 1.4.6). "Large" text is 18 pt, or 14 pt bold.
 *
 * @property normal the minimum ratio for body-size text.
 * @property large the minimum ratio for large text.
 */
public enum class WcagLevel(
    public val normal: Double,
    public val large: Double,
) {
    /** The floor for every theme, built-in or custom (docs/quality-bar.md). */
    AA(normal = 4.5, large = 3.0),

    /** What the Contrast theme promises. */
    AAA(normal = 7.0, large = 4.5),
    ;

    /** The ratio required for text of the given size class. */
    public fun required(large: Boolean): Double = if (large) this.large else normal
}
