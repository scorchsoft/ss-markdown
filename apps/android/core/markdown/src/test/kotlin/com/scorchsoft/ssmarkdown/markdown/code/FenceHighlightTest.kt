package com.scorchsoft.ssmarkdown.markdown.code

import com.scorchsoft.ssmarkdown.markdown.IncrementalTokenizer
import com.scorchsoft.ssmarkdown.markdown.LineState
import com.scorchsoft.ssmarkdown.markdown.LineTokenizer
import com.scorchsoft.ssmarkdown.markdown.NanoClock
import com.scorchsoft.ssmarkdown.markdown.TestDoc
import com.scorchsoft.ssmarkdown.markdown.TokenKind
import com.scorchsoft.ssmarkdown.markdown.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where highlighting starts and stops, which fences it applies to, and how
 * the incremental pass converges on code (docs/edge-cases.md, "Code
 * highlighting").
 */
class FenceHighlightTest {
    private fun kindsOf(
        doc: String,
        tokenizer: LineTokenizer = LineTokenizer(),
    ): List<Set<TokenKind>> {
        var state = LineState.DOCUMENT_START
        return doc.split('\n').map { line ->
            val result = tokenizer.tokenize(line, state)
            state = result.endState
            result.tokens.map { Tokens.kind(it) }.toSet()
        }
    }

    /** The kinds the code lexers paint: every `CODE_*` kind after the Markdown ones. */
    private val codeKinds = TokenKind.entries.filter { it.ordinal > TokenKind.FOOTNOTE_LABEL.ordinal }

    private fun Set<TokenKind>.hasCode() = any { it in codeKinds }

    @Test
    fun `the fence lines keep their Markdown styling and highlighting stops at the closing fence`() {
        val kinds = kindsOf("```python title=\"a.py\"\nx = '''open\n```\n# Heading '''")
        assertEquals(setOf(TokenKind.CODE_FENCE, TokenKind.CODE_FENCE_INFO), kinds[0])
        assertTrue(TokenKind.CODE_STRING in kinds[1])
        assertEquals("an open string does not hold the fence open", setOf(TokenKind.CODE_FENCE), kinds[2])
        assertEquals(setOf(TokenKind.HEADING_MARKER, TokenKind.HEADING_TEXT), kinds[3])
    }

    @Test
    fun `an unclosed fence highlights to the end of the document`() {
        val kinds = kindsOf("```js\nlet a = 1\n\n# not a heading\nconst b = 2")
        assertTrue(kinds[1].hasCode())
        assertTrue(TokenKind.CODE_KEYWORD in kinds[4])
        assertFalse(TokenKind.HEADING_MARKER in kinds[3])
    }

    @Test
    fun `indented code, unknown and missing languages stay plain`() {
        val plain =
            listOf("    def f(): return 1", "```\ndef f(): return 1\n```", "```brainfuck\ndef f(): return 1\n```")
        for (doc in plain) assertTrue(doc, kindsOf(doc).none { it.hasCode() })
    }

    @Test
    fun `tilde fences, longer fences and fences in containers are highlighted`() {
        assertTrue(TokenKind.CODE_KEYWORD in kindsOf("~~~ python\ndef f(): pass\n~~~")[1])
        val long = kindsOf("````py\n```\ndef f(): pass\n````\ndef")
        assertTrue("a shorter fence inside does not close it", TokenKind.CODE_KEYWORD in long[2])
        assertFalse(long[4].hasCode())
        val quoted = kindsOf("> ```c\n> int x = 0;")
        assertTrue(TokenKind.QUOTE_MARKER in quoted[1] && TokenKind.CODE_TYPE in quoted[1])
        val listed = kindsOf("- item\n\n  ```ts\n  let x: string\n  ```")
        assertTrue(TokenKind.CODE_TYPE in listed[3])
    }

    @Test
    fun `the language is the first word of the info string, any case, with aliases`() {
        assertEquals("python", CodeLanguages.languageOf("Python title=\"x\""))
        assertEquals("typescript", CodeLanguages.languageOf("TSX"))
        assertEquals("javascript", CodeLanguages.languageOf("js{1,3}"))
        assertEquals("cpp", CodeLanguages.languageOf("c++"))
        assertEquals("cpp", CodeLanguages.languageOf("h"))
        assertEquals("bash", CodeLanguages.languageOf("zsh"))
        assertEquals("html", CodeLanguages.languageOf("svg"))
        assertNull(CodeLanguages.languageOf("plaintext"))
        assertNull(CodeLanguages.languageOf(null))
        assertNull(CodeLanguages.languageOf(""))
        assertTrue(CodeLanguages.names.size >= REQUIRED_LANGUAGES.size)
        for (name in REQUIRED_LANGUAGES) assertEquals(name, CodeLanguages.languageOf(name))
    }

    @Test
    fun `the line state names the language, and highlighting can be turned off`() {
        val open = LineTokenizer().tokenize("```rb", LineState.NORMAL).endState
        assertEquals("ruby", open.codeLanguage)
        assertTrue(open.toString().contains("code=ruby"))
        val plain = LineTokenizer(highlightCode = false)
        assertNull(plain.tokenize("```rb", LineState.NORMAL).endState.codeLanguage)
        assertTrue(kindsOf("```rb\ndef x; end", plain).none { it.hasCode() })
    }

    @Test
    fun `typing inside a code line re-lexes that line only`() {
        val doc = TestDoc("```python\n" + (0 until LINES).joinToString("\n") { "x$it = $it  # n" } + "\n```")
        val cache = IncrementalTokenizer(doc)
        cache.continueInIdle(Long.MAX_VALUE / 4)
        val before = cache.linesTokenized
        doc.edit(cache, doc.offsetOf(LINES / 2) + 1, doc.offsetOf(LINES / 2) + 1, "y")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(1L, cache.linesTokenized - before)
    }

    @Test
    fun `opening a block comment at the top is bounded per frame and converges when it closes`() {
        val doc = TestDoc("```c\n" + (0 until LINES).joinToString("\n") { "int x$it = $it;" } + "\n```\n# After")
        val cache = IncrementalTokenizer(doc)
        cache.continueInIdle(Long.MAX_VALUE / 4)

        doc.edit(cache, doc.offsetOf(1), doc.offsetOf(1), "/* ")
        val clock = StepClock()
        val before = cache.linesTokenized
        cache.tokenizeUntil(VIEWPORT, budgetNanos = VIEWPORT * StepClock.STEP, clock = clock)
        val viewportLines = cache.linesTokenized - before
        assertTrue("one frame lexes about the viewport, not the block: $viewportLines", viewportLines <= VIEWPORT + 1)
        assertTrue(Tokens.kind(cache.resultAt(VIEWPORT / 2).tokens.last()) == TokenKind.CODE_COMMENT)
        // A line far below, not reached yet, is answered by a bounded guess.
        val far = cache.resultAt(LINES - 10)
        assertTrue(far.tokens.isNotEmpty())
        assertTrue(cache.linesTokenizedForDisplay <= IncrementalTokenizer.HEURISTIC_LOOKBACK + 2)

        // Idle slices finish the block: every line is now a comment, the heading after the fence is not.
        while (!cache.continueInIdle(budgetNanos = 50 * StepClock.STEP, clock = clock)) Unit
        assertEquals(TokenKind.CODE_COMMENT, Tokens.kind(cache.resultAt(LINES).tokens.single()))
        assertEquals(TokenKind.HEADING_MARKER, Tokens.kind(cache.resultAt(LINES + 2).tokens.first()))

        // Closing it two lines down converges right after the close.
        val afterOpen = cache.linesTokenized
        doc.edit(cache, doc.offsetOf(3), doc.offsetOf(3), "*/ ")
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertTrue(Tokens.kind(cache.resultAt(4).tokens.first()) != TokenKind.CODE_COMMENT)
        // Lines 3 to the closing fence re-lex: every line below was stored as "inside the comment", so
        // the states only agree again at the fence, which ends the block either way.
        assertEquals(LINES.toLong() - 1, cache.linesTokenized - afterOpen)
    }

    /** A clock that advances [STEP] every read, so a budget is a line count. */
    private class StepClock : NanoClock {
        private var now = 0L

        override fun nanoTime(): Long = now.also { now += STEP }

        companion object {
            const val STEP = 1_000L
        }
    }

    private companion object {
        const val LINES = 5_000
        const val VIEWPORT = 40
        val REQUIRED_LANGUAGES =
            listOf(
                "python",
                "javascript",
                "typescript",
                "java",
                "kotlin",
                "c",
                "cpp",
                "csharp",
                "go",
                "rust",
                "php",
                "ruby",
                "swift",
                "dart",
                "sql",
                "bash",
                "powershell",
                "json",
                "jsonc",
                "yaml",
                "toml",
                "html",
                "css",
                "scss",
                "less",
                "markdown",
                "dockerfile",
                "diff",
                "ini",
            )
    }
}
