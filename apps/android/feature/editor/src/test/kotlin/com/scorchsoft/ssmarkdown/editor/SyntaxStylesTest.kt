package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.markdown.InlineStyle
import com.scorchsoft.ssmarkdown.markdown.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxStylesTest {
    private val theme = EditorTheme.LIGHT
    private val styles = SyntaxStyles(theme)

    @Test
    fun `headings are bold ink and their markers ink`() {
        val text = styles.styleFor(TokenKind.HEADING_TEXT, 0)
        assertTrue(text.bold)
        assertEquals(theme.text, text.color)
        assertEquals(theme.blockMarker, styles.styleFor(TokenKind.HEADING_MARKER, 0).color)
    }

    @Test
    fun `inline markers and URLs are muted`() {
        assertEquals(theme.inlineMarker, styles.styleFor(TokenKind.STRONG_MARKER, InlineStyle.STRONG).color)
        assertEquals(theme.inlineMarker, styles.styleFor(TokenKind.LINK_URL, 0).color)
        assertNotEquals(theme.text, theme.inlineMarker)
    }

    @Test
    fun `links are blue and inline code sits on a chip`() {
        assertEquals(theme.link, styles.styleFor(TokenKind.LINK_TEXT, InlineStyle.LINK).color)
        assertEquals(theme.link, styles.styleFor(TokenKind.PLAIN, InlineStyle.LINK).color)
        assertEquals(theme.codeSpanBackground, styles.styleFor(TokenKind.CODE_SPAN_TEXT, 0).background)
        assertEquals(0, styles.styleFor(TokenKind.PLAIN, 0).background)
    }

    @Test
    fun `nested flags combine`() {
        val style =
            styles.styleFor(
                TokenKind.PLAIN,
                InlineStyle.STRONG or InlineStyle.EMPHASIS or InlineStyle.STRIKETHROUGH,
            )
        assertTrue(style.bold)
        assertTrue(style.italic)
        assertTrue(style.strikethrough)
        assertFalse(styles.plain.bold)
    }

    @Test
    fun `styles are built once and reused`() {
        assertSame(styles.styleFor(TokenKind.EMPHASIS_TEXT, 1), styles.styleFor(TokenKind.EMPHASIS_TEXT, 1))
    }

    @Test
    fun `the dark theme comes from the dark tokens and never uses red`() {
        val dark = EditorTheme.DARK
        assertNotEquals(EditorTheme.LIGHT.background, dark.background)
        assertEquals(0xFF161618.toInt(), dark.background)
        val red = 0xFFB3191B.toInt()
        assertFalse(listOf(theme.caret, theme.link, theme.blockMarker, theme.inlineMarker).contains(red))
    }
}
