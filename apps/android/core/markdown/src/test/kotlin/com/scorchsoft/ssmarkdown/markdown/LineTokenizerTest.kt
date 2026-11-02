package com.scorchsoft.ssmarkdown.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Token correctness, one table row per construct: the line, then its
 * non-plain tokens as `KIND:text`. A failure prints the row, so a broken
 * rule names itself.
 */
class LineTokenizerTest {
    private val cases: List<Pair<String, List<String>>> =
        listOf(
            "plain words" to listOf(),
            "# Title" to listOf("HEADING_MARKER:#", "HEADING_TEXT: Title"),
            "###### Six ##" to listOf("HEADING_MARKER:######", "HEADING_TEXT: Six ", "HEADING_MARKER:##"),
            "####### seven" to listOf(),
            "#hashtag" to listOf(),
            "*em*" to listOf("EMPHASIS_MARKER:*", "EMPHASIS_TEXT:em", "EMPHASIS_MARKER:*"),
            "_em_" to listOf("EMPHASIS_MARKER:_", "EMPHASIS_TEXT:em", "EMPHASIS_MARKER:_"),
            "snake_case_name" to listOf(),
            "**strong**" to listOf("STRONG_MARKER:**", "STRONG_TEXT:strong", "STRONG_MARKER:**"),
            "a * b * c" to listOf(),
            "~~gone~~" to listOf("STRIKETHROUGH_MARKER:~~", "STRIKETHROUGH_TEXT:gone", "STRIKETHROUGH_MARKER:~~"),
            "`code`" to listOf("CODE_SPAN_MARKER:`", "CODE_SPAN_TEXT:code", "CODE_SPAN_MARKER:`"),
            "``a ` b``" to listOf("CODE_SPAN_MARKER:``", "CODE_SPAN_TEXT:a ` b", "CODE_SPAN_MARKER:``"),
            "`*not em*`" to listOf("CODE_SPAN_MARKER:`", "CODE_SPAN_TEXT:*not em*", "CODE_SPAN_MARKER:`"),
            "``unclosed" to listOf(),
            "```kotlin" to listOf("CODE_FENCE:```", "CODE_FENCE_INFO:kotlin"),
            "~~~" to listOf("CODE_FENCE:~~~"),
            "[text](http://x.y \"T\")" to
                listOf(
                    "LINK_PUNCTUATION:[",
                    "LINK_TEXT:text",
                    "LINK_PUNCTUATION:](",
                    "LINK_URL:http://x.y",
                    "LINK_PUNCTUATION: ",
                    "LINK_TITLE:\"T\"",
                    "LINK_PUNCTUATION:)",
                ),
            "![alt](a.png)" to
                listOf(
                    "IMAGE_MARKER:!",
                    "LINK_PUNCTUATION:[",
                    "LINK_TEXT:alt",
                    "LINK_PUNCTUATION:](",
                    "LINK_URL:a.png",
                    "LINK_PUNCTUATION:)",
                ),
            "[a][ref]" to
                listOf(
                    "LINK_PUNCTUATION:[",
                    "LINK_TEXT:a",
                    "LINK_PUNCTUATION:][",
                    "LINK_LABEL:ref",
                    "LINK_PUNCTUATION:]",
                ),
            "[shortcut]" to listOf(),
            "<https://example.com>" to listOf("AUTOLINK:<https://example.com>"),
            "<me@example.com>" to listOf("AUTOLINK:<me@example.com>"),
            "see https://x.io/a." to listOf("AUTOLINK:https://x.io/a"),
            "[ref]: /url \"Title\"" to
                listOf(
                    "LINK_PUNCTUATION:[",
                    "LINK_LABEL:ref",
                    "LINK_PUNCTUATION:]: ",
                    "LINK_URL:/url",
                    "LINK_TITLE:\"Title\"",
                ),
            "- item" to listOf("LIST_MARKER:-"),
            "12. item" to listOf("LIST_MARKER:12."),
            "- [ ] todo" to listOf("LIST_MARKER:-", "TASK_OPEN:[ ]"),
            "* [x] done" to listOf("LIST_MARKER:*", "TASK_DONE:[x]"),
            "> quote" to listOf("QUOTE_MARKER:>"),
            "> > nested" to listOf("QUOTE_MARKER:>", "QUOTE_MARKER:>"),
            "---" to listOf("HORIZONTAL_RULE:---"),
            "* * *" to listOf("HORIZONTAL_RULE:* * *"),
            "a <b>bold</b>" to listOf("HTML_TAG:<b>", "HTML_TAG:</b>"),
            "x <!-- note --> y" to listOf("HTML_TAG:<!-- note -->"),
            "<div class=\"x\">" to listOf("HTML_BLOCK:<div class=\"x\">"),
            "\$e=mc^2\$" to listOf("MATH_DELIMITER:$", "MATH_TEXT:e=mc^2", "MATH_DELIMITER:$"),
            "costs \$5 and \$10" to listOf(),
            "\$\$x\$\$" to listOf("MATH_DELIMITER:$$", "MATH_TEXT:x", "MATH_DELIMITER:$$"),
            "\$\$" to listOf("MATH_DELIMITER:$$"),
            "note[^1]" to listOf("FOOTNOTE_REF:[^1]"),
            "[^1]: the note" to listOf("FOOTNOTE_LABEL:[^1]:"),
            "\\*not em\\*" to listOf("ESCAPE:\\", "ESCAPE:\\"),
            "    indented code" to listOf("CODE_BLOCK_TEXT:    indented code"),
            "**[link](u)**" to
                listOf(
                    "STRONG_MARKER:**",
                    "LINK_PUNCTUATION:[",
                    "LINK_TEXT:link",
                    "LINK_PUNCTUATION:](",
                    "LINK_URL:u",
                    "LINK_PUNCTUATION:)",
                    "STRONG_MARKER:**",
                ),
            "*a **b** c*" to
                listOf(
                    "EMPHASIS_MARKER:*",
                    "EMPHASIS_TEXT:a ",
                    "STRONG_MARKER:**",
                    "STRONG_TEXT:b",
                    "STRONG_MARKER:**",
                    "EMPHASIS_TEXT: c",
                    "EMPHASIS_MARKER:*",
                ),
            "[a *b*](u)" to
                listOf(
                    "LINK_PUNCTUATION:[",
                    "LINK_TEXT:a ",
                    "EMPHASIS_MARKER:*",
                    "EMPHASIS_TEXT:b",
                    "EMPHASIS_MARKER:*",
                    "LINK_PUNCTUATION:](",
                    "LINK_URL:u",
                    "LINK_PUNCTUATION:)",
                ),
            "[x](a_b_c)" to
                listOf(
                    "LINK_PUNCTUATION:[",
                    "LINK_TEXT:x",
                    "LINK_PUNCTUATION:](",
                    "LINK_URL:a_b_c",
                    "LINK_PUNCTUATION:)",
                ),
        )

    @Test
    fun `each construct gets its tokens`() {
        for ((line, expected) in cases) assertEquals("tokens of \"$line\"", expected, spans(line))
    }

    @Test
    fun `tokens cover the line contiguously and merge equal neighbours`() {
        for ((line, _) in cases) {
            val tokens = LineTokenizer().tokenize(line, LineState.NORMAL).tokens
            var column = 0
            for ((i, token) in tokens.withIndex()) {
                assertEquals("gap in \"$line\"", column, Tokens.start(token))
                assertTrue(Tokens.end(token) > Tokens.start(token))
                if (i > 0) {
                    val previous = tokens[i - 1]
                    val same =
                        Tokens.kind(previous) == Tokens.kind(token) && Tokens.styles(previous) == Tokens.styles(token)
                    assertTrue("unmerged neighbours in \"$line\"", !same)
                }
                column = Tokens.end(token)
            }
            assertEquals("coverage of \"$line\"", line.length, column)
        }
    }

    @Test
    fun `nested styles are carried as flags`() {
        val line = "**[a *b*](u)**"
        val tokens = LineTokenizer().tokenize(line, LineState.NORMAL).tokens
        val b = tokens.first { line.substring(Tokens.start(it), Tokens.end(it)) == "b" }
        assertEquals(TokenKind.EMPHASIS_TEXT, Tokens.kind(b))
        assertEquals(InlineStyle.STRONG or InlineStyle.LINK or InlineStyle.EMPHASIS, Tokens.styles(b))
    }

    @Test
    fun `heading line kinds and levels`() {
        val result = LineTokenizer().tokenize("### Three", LineState.NORMAL)
        assertEquals(LineKind.HEADING, result.kind)
        assertEquals(3, result.headingLevel)
    }

    @Test
    fun `a reference definition reports its normalised label`() {
        assertEquals("foo bar", LineTokenizer().tokenize("[Foo   BAR]: /u", LineState.NORMAL).definedLabel)
        assertEquals("^note", LineTokenizer().tokenize("[^Note]: text", LineState.NORMAL).definedLabel)
    }

    @Test
    fun `a long line is tokenised only up to the cap`() {
        val head = "# " + "*a* ".repeat(5_000)
        val line = CountingChars(head + "x".repeat(1_000_000))
        val result = LineTokenizer().tokenize(line, LineState.NORMAL)
        val last = result.tokens.last()
        assertEquals(LineTokenizer.MAX_TOKENIZED_COLUMNS, Tokens.end(last))
        assertEquals(LineKind.HEADING, result.kind)
        // Work, not wall-clock time (CC-F): nothing past the cap is read, and the prefix only a few times over.
        assertTrue("read up to ${line.furthest}", line.furthest < LineTokenizer.MAX_TOKENIZED_COLUMNS)
        assertTrue("${line.reads} char reads", line.reads <= LineTokenizer.MAX_TOKENIZED_COLUMNS * READS_PER_CHAR)
    }

    @Test
    fun `hostile inline lines stay linear`() {
        val hostile =
            listOf(
                "*a ".repeat(3_000),
                "[".repeat(10_000),
                "`".repeat(5_000) + "x" + "``".repeat(2_000),
                "_*".repeat(5_000),
                "<a ".repeat(3_000),
                "\$".repeat(10_000),
            )
        val tokenizer = LineTokenizer()
        for (text in hostile) {
            val line = CountingChars(text)
            tokenizer.tokenize(line, LineState.NORMAL)
            // A quadratic scan reads tens of millions of chars here; a linear one, a small multiple of the line.
            val read = minOf(text.length, LineTokenizer.MAX_TOKENIZED_COLUMNS)
            assertTrue("\"${text.take(10)}...\" read ${line.reads} chars", line.reads <= read.toLong() * READS_PER_CHAR)
        }
    }

    /** A line that counts how often, and how far, the tokenizer reads it. */
    private class CountingChars(
        private val text: String,
    ) : CharSequence {
        var reads = 0L
        var furthest = -1

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            reads++
            if (index > furthest) furthest = index
            return text[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence {
            reads += endIndex - startIndex
            if (endIndex - 1 > furthest) furthest = endIndex - 1
            return text.subSequence(startIndex, endIndex)
        }

        override fun toString(): String = error("the tokenizer must not copy the whole line")
    }

    @Test
    fun `the end state is shared when nothing carries`() {
        val tokenizer = LineTokenizer()
        val first = tokenizer.tokenize("a paragraph", LineState.NORMAL).endState
        val second = tokenizer.tokenize("more text", first).endState
        assertTrue(first === second)
        assertTrue(tokenizer.tokenize("", first).endState === LineState.NORMAL)
    }

    @Test
    fun `tokens pack and unpack`() {
        val token = Tokens.pack(12, 9_999, TokenKind.FOOTNOTE_LABEL, InlineStyle.LINK or InlineStyle.STRONG)
        assertEquals(12, Tokens.start(token))
        assertEquals(9_999, Tokens.end(token))
        assertEquals(TokenKind.FOOTNOTE_LABEL, Tokens.kind(token))
        assertEquals(InlineStyle.LINK or InlineStyle.STRONG, Tokens.styles(token))
        assertEquals("12-9999:FOOTNOTE_LABEL/10", Tokens.describe(longArrayOf(token)))
    }

    private companion object {
        /** Reads per char the tokenizer may make: at most 6 measured on these lines, so 16 leaves margin, not slack. */
        const val READS_PER_CHAR = 16L
    }
}
