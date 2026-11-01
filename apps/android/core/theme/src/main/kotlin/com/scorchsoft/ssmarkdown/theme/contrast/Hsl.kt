package com.scorchsoft.ssmarkdown.theme.contrast

import com.scorchsoft.ssmarkdown.theme.ThemeColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A colour in HSL. The contrast fixer moves only [lightness], so a fixed
 * colour keeps the hue and saturation its author picked; for a fixed hue
 * and saturation every RGB channel is non-decreasing in lightness, which
 * is what makes the fixer's binary search sound.
 */
internal data class Hsl(
    val hue: Double,
    val saturation: Double,
    val lightness: Double,
) {
    fun withLightness(value: Double): Hsl = copy(lightness = value.coerceIn(0.0, 1.0))

    @Suppress("MagicNumber") // The six hue sextants of the standard HSL-to-RGB table.
    fun toColor(): ThemeColor {
        val chroma = (1 - abs(2 * lightness - 1)) * saturation
        val h = hue / SEXTANT
        val x = chroma * (1 - abs(h % 2 - 1))
        val (r, g, b) =
            when (h.toInt()) {
                0 -> Triple(chroma, x, 0.0)
                1 -> Triple(x, chroma, 0.0)
                2 -> Triple(0.0, chroma, x)
                3 -> Triple(0.0, x, chroma)
                4 -> Triple(x, 0.0, chroma)
                else -> Triple(chroma, 0.0, x)
            }
        val m = lightness - chroma / 2
        return ThemeColor.of(channel(r + m), channel(g + m), channel(b + m))
    }

    companion object {
        private const val SEXTANT = 60.0
        private const val FULL_TURN = 360.0
        private const val CHANNEL_MAX = 255.0
        private const val GREEN_OFFSET = 2.0
        private const val BLUE_OFFSET = 4.0

        @Suppress("MagicNumber") // Six sextants: the standard RGB-to-HSL hue formula.
        fun of(color: ThemeColor): Hsl {
            val r = color.red / CHANNEL_MAX
            val g = color.green / CHANNEL_MAX
            val b = color.blue / CHANNEL_MAX
            val high = max(r, max(g, b))
            val low = min(r, min(g, b))
            val lightness = (high + low) / 2
            val delta = high - low
            if (delta == 0.0) return Hsl(0.0, 0.0, lightness)
            val saturation = delta / (1 - abs(2 * lightness - 1))
            val sector =
                when (high) {
                    r -> ((g - b) / delta).mod(6.0)
                    g -> (b - r) / delta + GREEN_OFFSET
                    else -> (r - g) / delta + BLUE_OFFSET
                }
            return Hsl((sector * SEXTANT).mod(FULL_TURN), saturation.coerceIn(0.0, 1.0), lightness)
        }

        private fun channel(value: Double): Int = (value.coerceIn(0.0, 1.0) * CHANNEL_MAX).roundToInt()
    }
}
