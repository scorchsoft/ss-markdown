package com.scorchsoft.ssmarkdown.theme.contrast

import com.scorchsoft.ssmarkdown.theme.ThemeColor
import kotlin.math.abs

/**
 * The result of nudging one colour until it reads on its background.
 *
 * @property color the colour to use: the original if it already passed.
 * @property ratio its contrast against the background.
 * @property reached false only when no lightness reaches the target (for
 *   example AAA on a mid-grey background); [color] is then the best
 *   available, black or white.
 */
public data class ContrastFix(
    val color: ThemeColor,
    val ratio: Double,
    val reached: Boolean,
)

/**
 * The theme editor's "Fix it" (mockup MoreStates, "Theme editor
 * problems"). It changes only HSL lightness and picks the passing value
 * nearest the original, darker or lighter, so the author's hue survives
 * and the change is as small as it can be.
 */
public object ContrastFixer {
    private const val SEARCH_STEPS = 40
    private const val ROUNDING_STEP = 1.0 / 255.0
    private const val MAX_ROUNDING_STEPS = 8

    /** Returns [foreground] adjusted to reach [target] against [background]. */
    public fun fix(
        foreground: ThemeColor,
        background: ThemeColor,
        target: Double,
    ): ContrastFix {
        val current = Contrast.ratio(foreground, background)
        if (current >= target) return ContrastFix(foreground, current, reached = true)

        val hsl = Hsl.of(foreground)
        val candidates =
            listOfNotNull(
                search(hsl, background, target, towards = 0.0),
                search(hsl, background, target, towards = 1.0),
            )
        val best = candidates.minByOrNull { abs(Hsl.of(it).lightness - hsl.lightness) }
        if (best != null) return ContrastFix(best, Contrast.ratio(best, background), reached = true)

        val extreme = listOf(ThemeColor.BLACK, ThemeColor.WHITE).maxBy { Contrast.ratio(it, background) }
        return ContrastFix(extreme, Contrast.ratio(extreme, background), reached = false)
    }

    /**
     * Binary-searches lightness between the original and [towards] (0 or 1)
     * for the value closest to the original that passes. Null when even
     * the end of the range fails.
     */
    private fun search(
        start: Hsl,
        background: ThemeColor,
        target: Double,
        towards: Double,
    ): ThemeColor? {
        if (Contrast.ratio(start.withLightness(towards).toColor(), background) < target) return null
        var failing = start.lightness
        var passing = towards
        repeat(SEARCH_STEPS) {
            val middle = (failing + passing) / 2
            if (Contrast.ratio(start.withLightness(middle).toColor(), background) >= target) {
                passing = middle
            } else {
                failing = middle
            }
        }
        return settle(start, passing, background, target, towards)
    }

    /** Rounding to 8-bit channels can undo the last fraction; step on until it passes. */
    private fun settle(
        start: Hsl,
        lightness: Double,
        background: ThemeColor,
        target: Double,
        towards: Double,
    ): ThemeColor {
        val direction = if (towards > start.lightness) 1 else -1
        var value = lightness
        var color = start.withLightness(value).toColor()
        var steps = 0
        while (Contrast.ratio(color, background) < target && steps < MAX_ROUNDING_STEPS) {
            value += direction * ROUNDING_STEP
            color = start.withLightness(value).toColor()
            steps++
        }
        return if (Contrast.ratio(color, background) >= target) color else start.withLightness(towards).toColor()
    }
}
