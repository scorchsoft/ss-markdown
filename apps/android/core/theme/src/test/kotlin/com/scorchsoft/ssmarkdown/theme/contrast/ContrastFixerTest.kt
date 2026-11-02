package com.scorchsoft.ssmarkdown.theme.contrast

import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.ThemeColor
import com.scorchsoft.ssmarkdown.theme.ThemeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ContrastFixerTest {
    private fun c(hex: String) = requireNotNull(ThemeColor.parse(hex))

    @Test
    fun `a passing colour is returned unchanged`() {
        val fix = ContrastFixer.fix(c("#1D5DBF"), ThemeColor.WHITE, 4.5)
        assertEquals(c("#1D5DBF"), fix.color)
        assertTrue(fix.reached)
    }

    @Test
    fun `a pale link on white is darkened just enough, keeping its hue`() {
        val original = c("#6FA0F0")
        val fix = ContrastFixer.fix(original, ThemeColor.WHITE, 4.5)
        assertTrue(fix.reached)
        assertTrue(fix.ratio >= 4.5)
        assertTrue("barely over the line: ${fix.ratio}", fix.ratio < 4.7)
        assertEquals(Hsl.of(original).hue, Hsl.of(fix.color).hue, 3.0)
        assertTrue(Hsl.of(fix.color).lightness < Hsl.of(original).lightness)
    }

    @Test
    fun `dim text on a dark page is lightened`() {
        val fix = ContrastFixer.fix(c("#555555"), c("#161618"), 4.5)
        assertTrue(fix.ratio >= 4.5)
        assertTrue(Hsl.of(fix.color).lightness > Hsl.of(c("#555555")).lightness)
    }

    @Test
    fun `an unreachable target falls back to the best extreme`() {
        // On mid grey, neither black nor white reaches 7:1.
        val fix = ContrastFixer.fix(c("#808080"), c("#777777"), 7.0)
        assertFalse(fix.reached)
        assertEquals(ThemeColor.BLACK, fix.color)
    }

    @Test
    fun `property - every reachable target is met, from random colours`() {
        val random = Random(42)
        repeat(2_000) {
            val fg = ThemeColor.of(random.nextInt(0x1000000))
            val bg = ThemeColor.of(random.nextInt(0x1000000))
            val target = if (random.nextBoolean()) 4.5 else 7.0
            val fix = ContrastFixer.fix(fg, bg, target)
            val reachable = maxOf(Contrast.ratio(ThemeColor.BLACK, bg), Contrast.ratio(ThemeColor.WHITE, bg)) >= target
            if (fix.reached) {
                assertTrue("$fg on $bg -> ${fix.color}", Contrast.ratio(fix.color, bg) >= target)
            }
            if (abs(Hsl.of(fg).saturation) < 1e-9 && reachable) assertTrue("grey $fg on $bg", fix.reached)
        }
    }

    @Test
    fun `withContrastFixed makes a failing theme pass and leaves backgrounds alone`() {
        val bad =
            BuiltInThemes.CLEAN.copy(
                light = BuiltInThemes.CLEAN.light.copy(link = c("#8FB8F5"), muted = c("#BBBBBB")),
                dark = BuiltInThemes.CLEAN.dark.copy(text = c("#3A3A3A")),
            )
        assertFalse(ContrastReport.of(bad).passes)
        val fixed = bad.withContrastFixed()
        assertTrue(ContrastReport.of(fixed).failures.joinToString { it.message() }, ContrastReport.of(fixed).passes)
        assertEquals(bad.light.bg, fixed.light.bg)
        assertEquals(bad.dark.bg, fixed.dark.bg)
        assertEquals(bad.light.text, fixed.light.text)
    }

    @Test
    fun `report message matches the mockup and never rounds a failure up`() {
        val link = c("#5E92E4") // 3.13:1 on white
        val theme = BuiltInThemes.CLEAN.copy(light = BuiltInThemes.CLEAN.light.copy(link = link))
        val failure = ContrastReport.of(theme).failures.single()
        assertEquals(ThemeVariant.LIGHT, failure.variant)
        assertEquals(ContrastPair.LINK, failure.pair)
        val expected = Regex("""Links are 3\.\d:1 on this background; 4\.5:1 is needed to read easily\.""")
        assertTrue(failure.message(), failure.message().matches(expected))
        val nearMiss = ContrastCheck(ThemeVariant.LIGHT, ContrastPair.TEXT, 4.47, 4.5, WcagLevel.AA)
        assertEquals("Body text is 4.4:1 on this background; 4.5:1 is needed to read easily.", nearMiss.message())
    }
}
