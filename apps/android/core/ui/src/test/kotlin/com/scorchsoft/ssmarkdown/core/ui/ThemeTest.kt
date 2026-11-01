package com.scorchsoft.ssmarkdown.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.scorchsoft.ssmarkdown.ui.strip.ComposeActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The theme hands its fonts to every style (UX2-12) and fills every
 * Material colour role from the tokens (UX2-9), in both themes.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    private val activity = ComposeActivityRule()

    @get:Rule val rules = activity.chain
    private val rule get() = activity.compose

    private val fonts = SSFonts(sans = FontFamily.Serif, mono = FontFamily.Cursive)

    @Test
    fun `the families handed to the theme reach the ladder and Material's typography`() {
        lateinit var ladder: List<TextStyle>
        lateinit var code: TextStyle
        lateinit var material: Typography
        rule.setContent {
            SSMarkdownTheme(darkTheme = false, fonts = fonts) {
                ladder =
                    listOf(
                        SS.type.caption,
                        SS.type.meta,
                        SS.type.label,
                        SS.type.body,
                        SS.type.bodyStrong,
                        SS.type.title,
                        SS.type.display,
                    )
                code = SS.type.code
                material = MaterialTheme.typography
            }
        }
        rule.waitForIdle()
        ladder.forEach { assertEquals(FontFamily.Serif, it.fontFamily) }
        assertEquals(FontFamily.Cursive, code.fontFamily)
        listOf(material.bodyLarge, material.labelLarge, material.titleLarge, material.labelMedium)
            .forEach { assertEquals(FontFamily.Serif, it.fontFamily) }
    }

    @Test
    fun `without fonts the theme falls back to the system families`() {
        lateinit var body: TextStyle
        rule.setContent { SSMarkdownTheme(darkTheme = false) { body = SS.type.body } }
        rule.waitForIdle()
        assertEquals(FontFamily.Default, body.fontFamily)
    }

    @Test
    fun `no Material colour role keeps its stock value`() {
        val seen = mutableMapOf<Boolean, Pair<ColorScheme, SSColors>>()
        rule.setContent {
            // One composition, both themes: a compose rule takes setContent once.
            for (dark in listOf(false, true)) {
                SSMarkdownTheme(darkTheme = dark) { seen[dark] = MaterialTheme.colorScheme to SS.colors }
            }
        }
        rule.waitForIdle()
        seen.forEach { (dark, pair) ->
            val (scheme, tokens) = pair
            val palette = tokenColours(tokens)
            val roles =
                mapOf(
                    "primaryContainer" to scheme.primaryContainer,
                    "secondary" to scheme.secondary,
                    "secondaryContainer" to scheme.secondaryContainer,
                    "tertiary" to scheme.tertiary,
                    "tertiaryContainer" to scheme.tertiaryContainer,
                    "surfaceContainer" to scheme.surfaceContainer,
                    "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                    "surfaceContainerHighest" to scheme.surfaceContainerHighest,
                    "surfaceContainerLow" to scheme.surfaceContainerLow,
                    "surfaceVariant" to scheme.surfaceVariant,
                    "outline" to scheme.outline,
                    "errorContainer" to scheme.errorContainer,
                    "inverseSurface" to scheme.inverseSurface,
                )
            roles.forEach { (name, colour) ->
                assertTrue("$name (dark=$dark) is not a token: $colour", colour in palette)
            }
            assertEquals("text fields outline with the 3:1 token", tokens.inputOutline, scheme.outline)
        }
        assertEquals(2, seen.size)
    }

    private fun tokenColours(c: SSColors): Set<Color> =
        setOf(
            c.bg,
            c.chrome,
            c.border,
            c.inputOutline,
            c.ink,
            c.inkMuted,
            c.gutter,
            c.needsYou,
            c.needsYouText,
            c.needsYouBg,
            c.onNeedsYou,
            c.info,
            c.infoBg,
            c.caution,
            c.cautionBg,
            c.segmentTrack,
            c.segmentSelected,
        )
}
