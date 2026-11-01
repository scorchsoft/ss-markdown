package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.markdown.InlineStyle
import com.scorchsoft.ssmarkdown.markdown.LineState
import com.scorchsoft.ssmarkdown.markdown.LineTokenizer
import com.scorchsoft.ssmarkdown.markdown.TokenKind
import com.scorchsoft.ssmarkdown.markdown.Tokens
import org.junit.Assert.assertEquals
import org.junit.Test

/** What gets drawn how: the runs a row is split into, from real tokenizer output. */
class RunPlannerTest {
    private data class Run(
        val start: Int,
        val end: Int,
        val kind: TokenKind,
        val flags: Int,
    )

    private fun plan(
        line: String,
        from: Int = 0,
        to: Int = line.length,
        tokens: LongArray = LineTokenizer().tokenize(line, LineState.DOCUMENT_START).tokens,
    ): List<Run> {
        val runs = mutableListOf<Run>()
        RunPlanner.plan(tokens, from, to, line.toCharArray(), 0) { s, e, k, f -> runs += Run(s, e, k, f) }
        return runs
    }

    @Test
    fun `a heading splits into its marker and its text`() {
        val kinds = plan("# Title").map { it.kind }.distinct()
        assertEquals(listOf(TokenKind.HEADING_MARKER, TokenKind.HEADING_TEXT), kinds)
    }

    @Test
    fun `strong text carries its flag and its markers are separate runs`() {
        val runs = plan("a **b** c")
        val strong = runs.single { it.kind == TokenKind.STRONG_TEXT }
        assertEquals(4 until 5, strong.start until strong.end)
        assertEquals(InlineStyle.STRONG, strong.flags and InlineStyle.STRONG)
        assertEquals(2, runs.count { it.kind == TokenKind.STRONG_MARKER })
    }

    @Test
    fun `runs are clipped to the visible columns`() {
        val runs = plan("plain `code` plain", from = 7, to = 9)
        assertEquals(listOf(Run(7, 9, TokenKind.CODE_SPAN_TEXT, 0)), runs)
    }

    @Test
    fun `tabs are gaps between runs`() {
        val runs = plan("a\tb", tokens = longArrayOf(Tokens.pack(0, 3, TokenKind.PLAIN)))
        assertEquals(listOf(Run(0, 1, TokenKind.PLAIN, 0), Run(2, 3, TokenKind.PLAIN, 0)), runs)
    }

    @Test
    fun `columns past the last token are plain`() {
        val runs = plan("abcdef", tokens = longArrayOf(Tokens.pack(0, 2, TokenKind.EMPHASIS_TEXT)))
        assertEquals(listOf(Run(0, 2, TokenKind.EMPHASIS_TEXT, 0), Run(2, 6, TokenKind.PLAIN, 0)), runs)
    }

    @Test
    fun `no tokens at all is one plain run`() {
        assertEquals(listOf(Run(0, 3, TokenKind.PLAIN, 0)), plan("abc", tokens = LongArray(0)))
    }

    @Test
    fun `every column of a row is planned exactly once`() {
        val line = "- [x] **done** and [a link](https://example.com) `x`"
        val covered = plan(line).flatMap { it.start until it.end }
        assertEquals((0 until line.length).toList(), covered)
    }
}
