package com.scorchsoft.ssmarkdown.theme.css

import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.PaletteKey
import com.scorchsoft.ssmarkdown.theme.ThemeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeCssTest {
    private val fakeFonts =
        object : FontFaceSource {
            override fun faces(family: String): List<FontFace> =
                if (family == "Inter") {
                    listOf(
                        FontFace("Inter", "$ASSETS/inter.ttf", false, 100..900, FontFormat.TRUETYPE),
                        FontFace("Inter", "$ASSETS/inter_italic.ttf", true, 400..400, FontFormat.TRUETYPE),
                    )
                } else {
                    emptyList()
                }

            override fun generic(family: String) = GenericFamily.SANS_SERIF
        }

    @Test
    fun `Clean light produces the documented variables`() {
        val css = ThemeCss.variables(BuiltInThemes.CLEAN, ThemeVariant.LIGHT, fakeFonts)
        listOf(
            "color-scheme: light;",
            "--ssm-bg: #FFFFFF;",
            "--ssm-link: #1D5DBF;",
            "--ssm-code-bg: #F4F4F5;",
            "--ssm-font-body: \"Inter\", sans-serif;",
            "--ssm-font-code: \"JetBrains Mono\", monospace;",
            "--ssm-size-base: 15px;",
            "--ssm-line-height: 1.6;",
            "--ssm-heading-scale: 1.25;",
            "--ssm-h1: 1.9531em;",
            "--ssm-h2: 1.5625em;",
            "--ssm-h3: 1.25em;",
            "--ssm-h4: 1em;",
        ).forEach { assertTrue("missing $it in\n$css", css.contains(it)) }
    }

    @Test
    fun `dark variant uses the dark palette`() {
        val css = ThemeCss.variables(BuiltInThemes.CLEAN, ThemeVariant.DARK)
        assertTrue(css.contains("color-scheme: dark;"))
        assertTrue(css.contains("--ssm-bg: #161618;"))
    }

    @Test
    fun `font faces are emitted once per family, with variable weight ranges`() {
        val css = ThemeCss.fontFaces(BuiltInThemes.CLEAN, fakeFonts)
        assertEquals(2, Regex("@font-face").findAll(css).count())
        assertTrue(css, css.contains("font-weight: 100 900;"))
        assertTrue(css, css.contains("font-style: italic; font-weight: 400;"))
        assertTrue(css, css.contains("format(\"truetype\")"))
    }

    @Test
    fun `strings are escaped so a hostile url cannot close the rule`() {
        val hostile =
            object : FontFaceSource by FontFaceSource.None {
                override fun faces(family: String) =
                    listOf(FontFace(family, HOSTILE_URL, false, 400..400, FontFormat.TRUETYPE))
            }
        val css = ThemeCss.fontFaces(BuiltInThemes.CLEAN, hostile)
        assertFalse(css, css.contains("x\")"))
        assertFalse(css, css.contains("</style"))
    }

    @Test
    fun `stylesheet appends advanced css last`() {
        val theme = BuiltInThemes.CLEAN.copy(css = "blockquote { font-style: italic; }")
        val css = ThemeCss.stylesheet(theme, ThemeVariant.LIGHT)
        assertTrue(css.trimEnd().endsWith("blockquote { font-style: italic; }"))
        assertTrue(css.indexOf(":root") < css.indexOf("blockquote"))
    }

    @Test
    fun `THEME-CSS md documents every variable the generator emits`() {
        val doc = File("THEME-CSS.md").readText()
        val emitted =
            Regex("""(--ssm-[a-z0-9-]+):""")
                .findAll(ThemeCss.variables(BuiltInThemes.CLEAN, ThemeVariant.LIGHT))
                .map { it.groupValues[1] }
                .toList()
        assertTrue(emitted.containsAll(PaletteKey.entries.map { it.cssVariable }))
        emitted.forEach { assertTrue("THEME-CSS.md does not document $it", doc.contains("`$it`")) }
    }

    private companion object {
        const val ASSETS = "https://appassets.androidplatform.net/fonts"
        const val HOSTILE_URL = "x\"); } body { color: red; } </style>"
    }
}
