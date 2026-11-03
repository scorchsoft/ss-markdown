package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.markdown.InlineStyle
import com.scorchsoft.ssmarkdown.markdown.TokenKind

/**
 * How one run of text is drawn. Every attribute keeps glyph advances
 * unchanged (fake bold, skew for italic) so a styled run sits exactly
 * where the plain-text measurement put it, and the caret never drifts.
 */
data class RunStyle(
    /** Text colour, ARGB. */
    val color: Int,
    /** Background behind the run, ARGB; 0 for none. */
    val background: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
)

/**
 * Decides the [RunStyle] for every [TokenKind] and [InlineStyle] mix under
 * an [EditorTheme]. Each kind and flag mix is built the first time it is
 * drawn and then kept, so drawing a run is an array lookup.
 */
class SyntaxStyles(
    /** The theme the styles are built from. */
    val theme: EditorTheme,
) {
    private val table = arrayOfNulls<RunStyle>(TokenKind.entries.size * FLAG_COMBINATIONS)

    /** The style for a run of [kind] inside the [InlineStyle] [flags]. */
    fun styleFor(
        kind: TokenKind,
        flags: Int,
    ): RunStyle {
        val index = kind.ordinal * FLAG_COMBINATIONS + (flags and FLAG_MASK)
        return table[index] ?: build(kind, flags and FLAG_MASK).also { table[index] = it }
    }

    /** The style of text no token covers. */
    val plain: RunStyle get() = styleFor(TokenKind.PLAIN, 0)

    private fun build(
        kind: TokenKind,
        flags: Int,
    ): RunStyle {
        val base = baseStyle(kind)
        val inLink = flags and InlineStyle.LINK != 0 && kind == TokenKind.PLAIN
        return base.copy(
            color = if (inLink) theme.link else base.color,
            bold = base.bold || flags and InlineStyle.STRONG != 0,
            italic = base.italic || flags and InlineStyle.EMPHASIS != 0,
            strikethrough = base.strikethrough || flags and InlineStyle.STRIKETHROUGH != 0,
        )
    }

    @Suppress("CyclomaticComplexMethod") // A flat lookup table written as a `when`; one arm per kind group.
    private fun baseStyle(kind: TokenKind): RunStyle =
        when (kind) {
            TokenKind.PLAIN, TokenKind.ESCAPE, TokenKind.MATH_TEXT -> RunStyle(theme.text)

            TokenKind.HEADING_TEXT -> RunStyle(theme.text, bold = true)

            TokenKind.HEADING_MARKER, TokenKind.SETEXT_UNDERLINE -> RunStyle(theme.blockMarker, bold = true)

            TokenKind.LIST_MARKER, TokenKind.QUOTE_MARKER, TokenKind.TABLE_PIPE, TokenKind.HORIZONTAL_RULE,
            TokenKind.TASK_OPEN, TokenKind.TASK_DONE,
            -> RunStyle(theme.blockMarker, bold = true)

            TokenKind.EMPHASIS_TEXT -> RunStyle(theme.text, italic = true)

            TokenKind.STRONG_TEXT -> RunStyle(theme.text, bold = true)

            TokenKind.STRIKETHROUGH_TEXT -> RunStyle(theme.text, strikethrough = true)

            TokenKind.CODE_SPAN_TEXT -> RunStyle(theme.codeText, background = theme.codeSpanBackground)

            TokenKind.CODE_SPAN_MARKER -> RunStyle(theme.inlineMarker, background = theme.codeSpanBackground)

            TokenKind.CODE_BLOCK_TEXT -> RunStyle(theme.codeText)

            TokenKind.LINK_TEXT, TokenKind.AUTOLINK, TokenKind.FOOTNOTE_REF -> RunStyle(theme.link)

            TokenKind.EMPHASIS_MARKER, TokenKind.STRONG_MARKER, TokenKind.STRIKETHROUGH_MARKER,
            TokenKind.CODE_FENCE, TokenKind.CODE_FENCE_INFO, TokenKind.LINK_PUNCTUATION, TokenKind.LINK_URL,
            TokenKind.LINK_TITLE, TokenKind.LINK_LABEL, TokenKind.IMAGE_MARKER, TokenKind.TABLE_DELIMITER_ROW,
            TokenKind.HTML_TAG, TokenKind.HTML_BLOCK, TokenKind.FRONT_MATTER_DELIMITER, TokenKind.FRONT_MATTER_TEXT,
            TokenKind.MATH_DELIMITER, TokenKind.FOOTNOTE_LABEL,
            -> RunStyle(theme.inlineMarker)

            else -> codeStyle(kind)
        }

    /** Highlighted code inside a fence, grouped as Preview's highlight.js stylesheet groups the classes. */
    private fun codeStyle(kind: TokenKind): RunStyle =
        when (kind) {
            TokenKind.CODE_COMMENT -> RunStyle(theme.codeComment, italic = true)
            TokenKind.CODE_KEYWORD, TokenKind.CODE_TYPE, TokenKind.CODE_LITERAL -> RunStyle(theme.codeKeyword)
            TokenKind.CODE_STRING, TokenKind.CODE_REGEXP, TokenKind.CODE_ADDITION -> RunStyle(theme.codeString)
            TokenKind.CODE_NUMBER, TokenKind.CODE_SYMBOL -> RunStyle(theme.codeNumber)
            TokenKind.CODE_FUNCTION, TokenKind.CODE_SECTION -> RunStyle(theme.codeTitle)
            TokenKind.CODE_ATTRIBUTE, TokenKind.CODE_VARIABLE -> RunStyle(theme.codeAttr)
            TokenKind.CODE_BUILT_IN, TokenKind.CODE_TAG -> RunStyle(theme.codeBuiltIn)
            TokenKind.CODE_META -> RunStyle(theme.codeMeta)
            TokenKind.CODE_DELETION -> RunStyle(theme.codeDeletion)
            else -> RunStyle(theme.codeText)
        }

    private companion object {
        /** InlineStyle packs into one byte of a token. */
        const val FLAG_COMBINATIONS = 256
        const val FLAG_MASK = 0xFF
    }
}
