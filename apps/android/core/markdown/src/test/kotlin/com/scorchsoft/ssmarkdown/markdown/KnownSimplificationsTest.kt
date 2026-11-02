package com.scorchsoft.ssmarkdown.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Code pane tokenizer's deliberate simplifications (docs/edge-cases.md,
 * "Known tokenizer simplifications"; listed on [LineState] and
 * [LineTokenizer]), pinned so each stays a decision: a change to any of
 * them fails here and has to update the register. They affect colouring
 * only; Preview renders with markdown-it and is unaffected.
 */
class KnownSimplificationsTest {
    @Test
    fun `a setext underline restyles only the line directly above it`() {
        // CommonMark makes both paragraph lines one heading; the Code pane styles the last.
        assertEquals(
            listOf(LineKind.PARAGRAPH, LineKind.HEADING, LineKind.SETEXT_UNDERLINE),
            kinds("first line\nsecond line\n==="),
        )
    }

    @Test
    fun `a link closes on its own line or is not a link`() {
        val lines = tokenizeFromScratch(listOf("see [the", "guide](u)"))
        val linkTokens = setOf(TokenKind.LINK_TEXT, TokenKind.LINK_URL)
        for ((i, line) in lines.withIndex()) {
            assertTrue("line $i was styled as a link", line.tokens.none { Tokens.kind(it) in linkTokens })
        }
    }

    @Test
    fun `containers nest at most eight deep`() {
        val quotes = spans("> ".repeat(LineState.MAX_CONTAINER_DEPTH + 2) + "deep")
        assertEquals(LineState.MAX_CONTAINER_DEPTH, quotes.count { it.startsWith("QUOTE_MARKER") })
    }

    @Test
    fun `a footnote definition does not continue onto the next line`() {
        val kinds = kinds("[^1]: the note\nmore of the note")
        assertNotEquals(LineKind.PARAGRAPH, kinds[0])
        assertEquals(LineKind.PARAGRAPH, kinds[1])
    }
}
