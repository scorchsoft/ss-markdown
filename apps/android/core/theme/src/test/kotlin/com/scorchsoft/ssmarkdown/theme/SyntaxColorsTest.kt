package com.scorchsoft.ssmarkdown.theme

import com.scorchsoft.ssmarkdown.theme.codec.ThemeCodec
import com.scorchsoft.ssmarkdown.theme.codec.ThemeDecodeResult
import com.scorchsoft.ssmarkdown.theme.contrast.ContrastPair
import com.scorchsoft.ssmarkdown.theme.contrast.ContrastReport
import com.scorchsoft.ssmarkdown.theme.contrast.withContrastFixed
import com.scorchsoft.ssmarkdown.theme.css.ThemeCss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The code-highlighting colours: the same palette as Preview's
 * highlight.js stylesheet, contrast-checked on the code background, and
 * optional in custom themes.
 */
class SyntaxColorsTest {
    @Test
    fun `the defaults are Preview's highlight_js palette, read from code_css`() {
        val css = File(CODE_CSS).readText()
        val scopes = parse(css)
        val light = scopes.getValue(Scope.LIGHT)
        val dark = scopes.getValue(Scope.DARK)
        val expected =
            mapOf(
                Scope.LIGHT to light,
                Scope.DARK to dark,
                Scope.CONTRAST_LIGHT to light + scopes.getValue(Scope.CONTRAST_LIGHT),
                Scope.CONTRAST_DARK to dark + scopes.getValue(Scope.CONTRAST_DARK),
            )
        val actual =
            mapOf(
                Scope.LIGHT to SyntaxColors.LIGHT,
                Scope.DARK to SyntaxColors.DARK,
                Scope.CONTRAST_LIGHT to SyntaxColors.CONTRAST_LIGHT,
                Scope.CONTRAST_DARK to SyntaxColors.CONTRAST_DARK,
            )
        for ((scope, colors) in actual) {
            val classes = expected.getValue(scope)
            for ((key, hljsClass) in REPRESENTATIVE) {
                assertEquals("$scope $key (.hljs-$hljsClass)", classes.getValue(hljsClass), colors[key].hex)
            }
        }
    }

    @Test
    fun `every built-in uses the defaults, Contrast its own AAA set`() {
        for (theme in BuiltInThemes.ALL) {
            val contrast = theme == BuiltInThemes.CONTRAST
            assertEquals(if (contrast) SyntaxColors.CONTRAST_LIGHT else SyntaxColors.LIGHT, theme.light.syntax)
            assertEquals(if (contrast) SyntaxColors.CONTRAST_DARK else SyntaxColors.DARK, theme.dark.syntax)
        }
    }

    @Test
    fun `every code colour is checked on the code background`() {
        val syntaxPairs = ContrastPair.entries.filter { it.foreground.isSyntax }
        assertEquals(PaletteKey.SYNTAX, syntaxPairs.map { it.foreground })
        assertTrue(syntaxPairs.all { it.background == PaletteKey.CODE_BG })
        // A custom theme that darkens the light code background fails on code colours, and Fix it repairs them.
        val dim = BuiltInThemes.CLEAN.copy(light = BuiltInThemes.CLEAN.light.copy(codeBg = ThemeColor.of(0x555555)))
        val failures = ContrastReport.of(dim).failures.map { it.pair }
        assertTrue(failures.toString(), ContrastPair.CODE_COMMENT in failures && ContrastPair.CODE_KEYWORD in failures)
        assertTrue(ContrastReport.of(dim.withContrastFixed()).passes)
    }

    @Test
    fun `a custom theme without code colours inherits its base's, and may set any`() {
        val json =
            """
            { "name": "Mine", "base": "contrast",
              "light": { "codeKeyword": "#112233" }, "dark": { "codeComment": "#ABCDEF" } }
            """.trimIndent()
        val theme = (ThemeCodec.decode(json) as ThemeDecodeResult.Decoded).theme
        assertEquals("#112233", theme.light.syntax.keyword.hex)
        assertEquals(SyntaxColors.CONTRAST_LIGHT.string, theme.light.syntax.string)
        assertEquals("#ABCDEF", theme.dark.syntax.comment.hex)
        assertEquals(SyntaxColors.CONTRAST_DARK.title, theme.dark.syntax.title)
        val again = (ThemeCodec.decode(ThemeCodec.encode(theme)) as ThemeDecodeResult.Decoded).theme
        assertEquals(theme.light, again.light)
        assertEquals(theme.dark, again.dark)
    }

    @Test
    fun `palette keys read and write code colours, and the CSS carries them`() {
        var palette = BuiltInThemes.CLEAN.light
        for (key in PaletteKey.SYNTAX) {
            palette = palette.with(key, ThemeColor.of(key.ordinal))
            assertEquals(key.ordinal, palette[key].rgb)
            assertEquals(palette.syntax[key], palette[key])
        }
        assertFalse(PaletteKey.CODE_BG.isSyntax)
        assertThrows(IllegalArgumentException::class.java) { SyntaxColors.LIGHT[PaletteKey.BG] }
        assertThrows(IllegalArgumentException::class.java) { SyntaxColors.LIGHT.with(PaletteKey.BG, ThemeColor.WHITE) }
        assertEquals(SyntaxColors.DARK, SyntaxColors.defaults(ThemeVariant.DARK))
        assertEquals(SyntaxColors.LIGHT, SyntaxColors.defaults(ThemeVariant.LIGHT))
        val css = ThemeCss.variables(BuiltInThemes.CLEAN, ThemeVariant.DARK)
        assertTrue(css.contains("--ssm-code-keyword: #D2A8FF;"))
    }

    private enum class Scope { LIGHT, DARK, CONTRAST_LIGHT, CONTRAST_DARK }

    /** `.hljs-*` class (without the prefix) to `#RRGGBB`, per scope, from code.css's rules. */
    private fun parse(css: String): Map<Scope, Map<String, String>> {
        val out = Scope.entries.associateWith { LinkedHashMap<String, String>() }
        val stripped = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for (rule in Regex("([^{}]+)\\{([^{}]*)}").findAll(stripped)) {
            val selector = rule.groupValues[1]
            val color = Regex("(?<![-\\w])color:\\s*#([0-9a-fA-F]{6})").find(rule.groupValues[2]) ?: continue
            val contrast = "data-theme=\"contrast\"" in selector
            val dark = "data-dark=\"true\"" in selector
            val scope =
                when {
                    contrast && dark -> Scope.CONTRAST_DARK
                    contrast -> Scope.CONTRAST_LIGHT
                    dark -> Scope.DARK
                    else -> Scope.LIGHT
                }
            for (match in Regex("\\.hljs-([a-z_]+)").findAll(selector)) {
                out.getValue(scope)[match.groupValues[1]] = "#" + color.groupValues[1].uppercase()
            }
        }
        return out
    }

    private companion object {
        /** From the module directory, where Gradle runs tests. */
        const val CODE_CSS = "../../feature/preview/web/src/styles/code.css"

        val REPRESENTATIVE =
            mapOf(
                PaletteKey.CODE_COMMENT to "comment",
                PaletteKey.CODE_KEYWORD to "keyword",
                PaletteKey.CODE_STRING to "string",
                PaletteKey.CODE_NUMBER to "number",
                PaletteKey.CODE_TITLE to "title",
                PaletteKey.CODE_ATTR to "attr",
                PaletteKey.CODE_BUILT_IN to "built_in",
                PaletteKey.CODE_META to "meta",
                PaletteKey.CODE_DELETION to "deletion",
            )
    }
}
