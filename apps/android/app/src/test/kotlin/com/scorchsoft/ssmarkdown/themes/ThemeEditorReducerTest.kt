package com.scorchsoft.ssmarkdown.themes

import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.PaletteKey
import com.scorchsoft.ssmarkdown.theme.ThemeVariant
import com.scorchsoft.ssmarkdown.theme.codec.ThemeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme editor's validation flow: every edit goes through the codec,
 * a refused edit says why and changes nothing, and contrast problems
 * come with a Fix it that works.
 */
class ThemeEditorReducerTest {
    private val start = ThemeEditorReducer.start(ThemeEditorReducer.copyOf(BuiltInThemes.READER), savedId = null)

    private fun ThemeEditorUiState.then(action: ThemeEditorAction) = ThemeEditorReducer.reduce(this, action)

    @Test
    fun `a built-in is edited as a custom copy`() {
        assertEquals("Reader copy", start.theme.name)
        assertEquals("reader", start.theme.base)
        assertTrue(start.theme.id.startsWith("custom-"))
        assertEquals("Reader", start.baseName)
    }

    @Test
    fun `a bad colour is refused with a message and nothing changes`() {
        val after = start.then(ThemeEditorAction.SetColor(PaletteKey.BG, "#12"))
        assertEquals(start.theme, after.theme)
        assertTrue(after.problems.single().contains("bg"))
    }

    @Test
    fun `a good colour lands in the chosen variant only`() {
        val after =
            start
                .then(
                    ThemeEditorAction.SelectVariant(ThemeVariant.DARK),
                ).then(ThemeEditorAction.SetColor(PaletteKey.LINK, "#abc"))
        assertEquals("#AABBCC", after.theme.dark.link.hex)
        assertEquals(start.theme.light.link, after.theme.light.link)
        assertTrue(after.dirty)
    }

    @Test
    fun `broken JSON keeps the theme and points at the line`() {
        val after =
            start.then(
                ThemeEditorAction.EditJson("{\n  \"name\": \"X\",\n  \"light\": { \"bg\": \"nope\" }\n}"),
            )
        assertEquals(start.theme, after.theme)
        assertTrue(after.problems.first().startsWith("line 3"))
    }

    @Test
    fun `valid JSON applies at once`() {
        val json = ThemeCodec.encode(start.theme.copy(name = "Studio Serif"))
        val after = start.then(ThemeEditorAction.EditJson(json))
        assertEquals("Studio Serif", after.theme.name)
        assertTrue(after.problems.isEmpty())
    }

    @Test
    fun `low contrast is reported and Fix it repairs it`() {
        val faint = start.then(ThemeEditorAction.SetColor(PaletteKey.TEXT, "#DDDDDD"))
        assertTrue(faint.contrast.isNotEmpty())
        val fixed = faint.then(ThemeEditorAction.FixContrast)
        assertTrue(fixed.contrast.isEmpty())
        assertNotEquals("#DDDDDD", fixed.theme.light.text.hex)
    }

    @Test
    fun `sizes step within their bounds`() {
        var state = start
        repeat(100) { state = state.then(ThemeEditorAction.StepBase(1)) }
        assertEquals(32.0, state.theme.size.base, 0.0)
        state = state.then(ThemeEditorAction.StepLineHeight(-1))
        assertEquals(1.65, state.theme.size.lineHeight, 1e-9)
    }

    @Test
    fun `remote CSS is refused`() {
        val after = start.then(ThemeEditorAction.EditCss("body { background: url(https://x.example/a.png) }"))
        assertEquals("", after.theme.css)
        assertTrue(after.problems.isNotEmpty())
    }

    @Test
    fun `an import that is not a theme says so in one line`() {
        val after = ThemeEditorReducer.imported(start, "midnight.json", "{ \"light\": { \"bg\": 3 } }")
        assertNotNull(after.banner)
        assertTrue(after.banner.orEmpty().startsWith("Couldn't import midnight.json"))
        assertEquals(start.theme, after.theme)
    }
}
