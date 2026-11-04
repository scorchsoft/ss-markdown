package com.scorchsoft.ssmarkdown.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.scorchsoft.ssmarkdown.ui.strip.PressedKeyFace
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Every token pair meets WCAG AA" (docs/quality-bar.md §3), checked for
 * both themes so a token edit cannot quietly break it.
 */
class SSColorsContrastTest {
    @Test
    fun `text pairs meet AA in both themes`() {
        for (c in listOf(SSLightColors, SSDarkColors)) {
            val theme = if (c.isDark) "dark" else "light"
            val textPairs =
                mapOf(
                    "ink on bg" to (c.ink to c.bg),
                    "ink on chrome" to (c.ink to c.chrome),
                    "muted on chrome" to (c.inkMuted to c.chrome),
                    "muted on segment track" to (c.inkMuted to c.segmentTrack),
                    "ink on selected segment" to (c.ink to c.segmentSelected),
                    "gutter on bg" to (c.gutter to c.bg),
                    "on needs-you" to (c.onNeedsYou to c.needsYou),
                    "info" to (c.info to c.infoBg),
                    "caution" to (c.caution to c.cautionBg),
                    // UX-4: error text sits on the chrome (status line, find bar),
                    // the page (dialogs) and the neutral banner box.
                    "needs-you text on chrome" to (c.needsYouText to c.chrome),
                    "needs-you text on bg" to (c.needsYouText to c.bg),
                    "needs-you text on segment track" to (c.needsYouText to c.segmentTrack),
                    // UX2-6: the error callout's title and glyph, and its body.
                    "needs-you text on its tint" to (c.needsYouText to c.needsYouBg),
                    "ink on the error tint" to (c.ink to c.needsYouBg),
                    // Compare's git-style rows: the line, its sign, and its numbers (muted) on each tint.
                    "added on its tint" to (c.diffAdded to c.diffAddedBg),
                    "removed on its tint" to (c.diffRemoved to c.diffRemovedBg),
                    "muted on the added tint" to (c.inkMuted to c.diffAddedBg),
                    "muted on the removed tint" to (c.inkMuted to c.diffRemovedBg),
                )
            textPairs.forEach { (name, pair) -> assertContrast("$theme $name", pair.first, pair.second, AA_TEXT) }
        }
    }

    @Test
    fun `needs-you marks stand out from the chrome`() {
        // The unsaved dot is a graphic, not text: AA asks 3:1 of it.
        for (c in listOf(SSLightColors, SSDarkColors)) {
            assertContrast("needs-you dot (dark=${c.isDark})", c.needsYou, c.chrome, AA_GRAPHIC)
        }
    }

    @Test
    fun `a text field's outline reaches 3 to 1 wherever a field sits`() {
        // UX2-10: WCAG 1.4.11. `border` (1.26:1) was the outline of every field.
        for (c in listOf(SSLightColors, SSDarkColors)) {
            for ((surface, color) in listOf("bg" to c.bg, "chrome" to c.chrome, "track" to c.segmentTrack)) {
                assertContrast("input outline on $surface (dark=${c.isDark})", c.inputOutline, color, AA_GRAPHIC)
            }
        }
    }

    @Test
    fun `a pressed strip key's ring stands out from the strip and its own fill`() {
        // UX-5: WCAG 1.4.11 asks 3:1 of a state indicator. The tint alone was 1.1:1.
        for (c in listOf(SSLightColors, SSDarkColors)) {
            val ring = PressedKeyFace.ring(c)
            assertContrast("pressed ring on strip (dark=${c.isDark})", ring, c.chrome, AA_GRAPHIC)
            assertContrast("pressed ring on its fill (dark=${c.isDark})", ring, PressedKeyFace.fill(c), AA_GRAPHIC)
        }
    }

    @Test
    fun `the fast-scroll thumb and its bubble read in both themes`() {
        // docs/ux-spec.md "Fast scroll": a graphic (3:1) on the page and the chrome, at rest and held;
        // the bubble's text on its fill (4.5:1) and its outline on the page (3:1).
        for (c in listOf(SSLightColors, SSDarkColors)) {
            val theme = if (c.isDark) "dark" else "light"
            assertContrast("$theme thumb on bg", c.gutter, c.bg, AA_GRAPHIC)
            assertContrast("$theme thumb on chrome", c.gutter, c.chrome, AA_GRAPHIC)
            assertContrast("$theme held thumb on bg", c.inkMuted, c.bg, AA_GRAPHIC)
            assertContrast("$theme bubble text", c.ink, c.chrome, AA_TEXT)
            assertContrast("$theme bubble outline on bg", c.inputOutline, c.bg, AA_GRAPHIC)
        }
    }

    private fun assertContrast(
        name: String,
        fg: Color,
        bg: Color,
        minimum: Double,
    ) {
        val ratio = contrast(fg, bg)
        assertTrue("$name is %.2f:1, needs %.1f:1".format(ratio, minimum), ratio >= minimum)
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val (lighter, darker) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }

    private companion object {
        const val AA_TEXT = 4.5
        const val AA_GRAPHIC = 3.0
    }
}
