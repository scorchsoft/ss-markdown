package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.markdown.InlineStyle
import com.scorchsoft.ssmarkdown.markdown.TokenKind
import com.scorchsoft.ssmarkdown.theme.SyntaxColors
import com.scorchsoft.ssmarkdown.theme.ThemeColor
import com.scorchsoft.ssmarkdown.theme.contrast.Contrast
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
    fun `code token kinds take the theme's code colours, grouped as Preview groups them`() {
        val expected =
            mapOf(
                TokenKind.CODE_COMMENT to theme.codeComment,
                TokenKind.CODE_KEYWORD to theme.codeKeyword,
                TokenKind.CODE_TYPE to theme.codeKeyword,
                TokenKind.CODE_LITERAL to theme.codeKeyword,
                TokenKind.CODE_STRING to theme.codeString,
                TokenKind.CODE_REGEXP to theme.codeString,
                TokenKind.CODE_ADDITION to theme.codeString,
                TokenKind.CODE_NUMBER to theme.codeNumber,
                TokenKind.CODE_SYMBOL to theme.codeNumber,
                TokenKind.CODE_FUNCTION to theme.codeTitle,
                TokenKind.CODE_SECTION to theme.codeTitle,
                TokenKind.CODE_ATTRIBUTE to theme.codeAttr,
                TokenKind.CODE_VARIABLE to theme.codeAttr,
                TokenKind.CODE_BUILT_IN to theme.codeBuiltIn,
                TokenKind.CODE_TAG to theme.codeBuiltIn,
                TokenKind.CODE_META to theme.codeMeta,
                TokenKind.CODE_DELETION to theme.codeDeletion,
            )
        for ((kind, color) in expected) assertEquals(kind.name, color, styles.styleFor(kind, 0).color)
        assertTrue("comments are italic, as in Preview", styles.styleFor(TokenKind.CODE_COMMENT, 0).italic)
        assertTrue("bold in a Markdown code block", styles.styleFor(TokenKind.CODE_BLOCK_TEXT, InlineStyle.STRONG).bold)
        assertEquals(theme.codeText, styles.styleFor(TokenKind.CODE_BLOCK_TEXT, 0).color)
    }

    @Test
    fun `a theme's code colours apply, and one unreadable on the code band falls back`() {
        val custom = SyntaxColors.LIGHT.copy(keyword = ThemeColor.of(0x112233), string = ThemeColor.of(0xF0F0F0))
        val applied = EditorTheme.LIGHT.withSyntax(custom)
        assertEquals(0xFF112233.toInt(), applied.codeKeyword)
        assertEquals(
            "near-white on the light band is unreadable",
            0xFF000000.toInt() or SyntaxColors.LIGHT.string.rgb,
            applied.codeString,
        )
        val onDark = EditorTheme.DARK.withSyntax(SyntaxColors.LIGHT.copy(keyword = ThemeColor.of(0x000000)))
        assertEquals(
            "black on the dark band falls back to the dark default",
            0xFF000000.toInt() or SyntaxColors.DARK.keyword.rgb,
            onDark.codeKeyword,
        )
        val grey = EditorTheme.LIGHT.copy(codeBlockBackground = 0xFF777777.toInt()).withSyntax(SyntaxColors.LIGHT)
        assertEquals("nothing reads on mid grey: plain code text", grey.codeText, grey.codeComment)
    }

    @Test
    fun `the built-in code colours read at AA on both code bands`() {
        for (editor in listOf(EditorTheme.LIGHT, EditorTheme.DARK)) {
            val band = ThemeColor.of(editor.codeBlockBackground)
            val colours =
                listOf(
                    editor.codeComment,
                    editor.codeKeyword,
                    editor.codeString,
                    editor.codeNumber,
                    editor.codeTitle,
                    editor.codeAttr,
                    editor.codeBuiltIn,
                    editor.codeMeta,
                    editor.codeDeletion,
                )
            for (colour in colours) {
                assertTrue(Contrast.ratio(ThemeColor.of(colour), band) >= EditorTheme.MIN_CODE_CONTRAST)
            }
        }
        assertEquals(0xFF000000.toInt() or SyntaxColors.DARK.keyword.rgb, EditorTheme.DARK.codeKeyword)
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
