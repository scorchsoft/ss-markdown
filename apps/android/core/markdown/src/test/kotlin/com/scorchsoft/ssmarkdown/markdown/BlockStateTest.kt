package com.scorchsoft.ssmarkdown.markdown

import com.scorchsoft.ssmarkdown.markdown.LineKind.BLANK
import com.scorchsoft.ssmarkdown.markdown.LineKind.CODE
import com.scorchsoft.ssmarkdown.markdown.LineKind.CODE_FENCE
import com.scorchsoft.ssmarkdown.markdown.LineKind.FRONT_MATTER
import com.scorchsoft.ssmarkdown.markdown.LineKind.HEADING
import com.scorchsoft.ssmarkdown.markdown.LineKind.HORIZONTAL_RULE
import com.scorchsoft.ssmarkdown.markdown.LineKind.HTML
import com.scorchsoft.ssmarkdown.markdown.LineKind.MATH
import com.scorchsoft.ssmarkdown.markdown.LineKind.PARAGRAPH
import com.scorchsoft.ssmarkdown.markdown.LineKind.SETEXT_UNDERLINE
import com.scorchsoft.ssmarkdown.markdown.LineKind.TABLE_DELIMITER
import com.scorchsoft.ssmarkdown.markdown.LineKind.TABLE_HEADER
import com.scorchsoft.ssmarkdown.markdown.LineKind.TABLE_ROW
import com.scorchsoft.ssmarkdown.markdown.LineState.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What carries from line to line: fences, front matter, HTML and math blocks, containers, setext and tables. */
class BlockStateTest {
    @Test
    fun `a fence carries its char, length and language until a long enough close`() {
        val text = "````kotlin\n# not a heading\n```\n````\n# heading"
        assertEquals(listOf(CODE_FENCE, CODE, CODE, CODE_FENCE, HEADING), kinds(text))
        val cache = fullyTokenized(TestDoc(text))
        val state = cache.resultAt(0).endState
        assertEquals(BlockMode.FENCED_CODE, state.mode)
        assertEquals('`', state.fenceChar)
        assertEquals(4, state.fenceLength)
        assertEquals("kotlin", state.fenceLanguage)
        assertEquals(LineState.NORMAL, cache.resultAt(3).endState)
    }

    @Test
    fun `a tilde fence is not closed by backticks`() {
        assertEquals(listOf(CODE_FENCE, CODE, CODE_FENCE), kinds("~~~\n```\n~~~"))
    }

    @Test
    fun `hash inside a fence is never a heading`() {
        val text = (listOf("```") + List(50) { "# fake $it" } + listOf("```", "# real")).joinToString("\n")
        val cache = fullyTokenized(TestDoc(text))
        assertEquals(listOf("real"), cache.outline.headings().map { it.text })
    }

    @Test
    fun `front matter is only recognised on line 0`() {
        assertEquals(listOf(FRONT_MATTER, FRONT_MATTER, FRONT_MATTER, HEADING), kinds("---\ntitle: x\n---\n# H"))
        assertEquals(listOf(FRONT_MATTER, FRONT_MATTER, FRONT_MATTER), kinds("+++\n# toml = 1\n+++"))
        assertEquals(listOf(BLANK, HORIZONTAL_RULE, PARAGRAPH), kinds("\n---\ntitle: x"))
        val headings = fullyTokenized(TestDoc("---\n# not a heading\n---\n")).outline.size
        assertEquals(0, headings)
    }

    @Test
    fun `html blocks end by type`() {
        assertEquals(listOf(HTML, HTML, HTML, PARAGRAPH), kinds("<!--\n# hidden\n-->\ntext"))
        assertEquals(listOf(HTML, HTML, BLANK, HEADING), kinds("<div>\n# inside\n\n# outside"))
        assertEquals(listOf(HTML, HTML, HTML, HEADING), kinds("<script>\nlet a = 1\n</script>\n# after"))
        assertEquals(listOf(HTML, HEADING), kinds("<!-- one line -->\n# after"))
    }

    @Test
    fun `math blocks carry until a closing pair of dollars`() {
        assertEquals(listOf(MATH, MATH, MATH, HEADING), kinds("$$\n# x^2\n$$\n# after"))
        assertEquals(listOf(MATH, HEADING), kinds("$$ x $$\n# after"))
    }

    @Test
    fun `setext underlines restyle the line above`() {
        val cache = fullyTokenized(TestDoc("Title\n=====\n\nSub\n---\n\n---"))
        assertEquals(
            listOf(
                HEADING,
                SETEXT_UNDERLINE,
                BLANK,
                HEADING,
                SETEXT_UNDERLINE,
                BLANK,
                HORIZONTAL_RULE,
            ),
            (0..6).map {
                cache.resultAt(it).kind
            },
        )
        assertEquals(listOf("HEADING_TEXT:Title"), spans("Title", cache.resultAt(0)))
        assertEquals(listOf(Heading(0, 1, "Title"), Heading(3, 2, "Sub")), cache.outline.headings())
    }

    @Test
    fun `a setext underline needs a paragraph in the same container`() {
        assertEquals(listOf(LineKind.PARAGRAPH, HORIZONTAL_RULE), kinds("> quoted\n---"))
        assertEquals(listOf(HEADING, SETEXT_UNDERLINE), kinds("> quoted\n> ---"))
        assertEquals(listOf(LineKind.PARAGRAPH, HORIZONTAL_RULE), kinds("- item\n---"))
    }

    @Test
    fun `tables need a header with the same number of cells`() {
        assertEquals(
            listOf(TABLE_HEADER, TABLE_DELIMITER, TABLE_ROW, TABLE_ROW, BLANK, PARAGRAPH),
            kinds("a | b\n--|:-:\n1 | 2\n3\n\nafter"),
        )
        assertEquals(listOf(PARAGRAPH, PARAGRAPH), kinds("a | b\n|---|"))
        val header = fullyTokenized(TestDoc("| a | b |\n|---|---|")).resultAt(0)
        assertEquals(listOf("TABLE_PIPE:|", "TABLE_PIPE:|", "TABLE_PIPE:|"), spans("| a | b |", header))
    }

    @Test
    fun `indented lines are code only outside paragraphs and list items`() {
        assertEquals(listOf(PARAGRAPH, PARAGRAPH), kinds("text\n    more"))
        assertEquals(listOf(BLANK, CODE), kinds("\n    code"))
        assertEquals(listOf(PARAGRAPH, BLANK, PARAGRAPH), kinds("- item\n\n    continued"))
        assertEquals(listOf(PARAGRAPH, BLANK, CODE), kinds("- item\n\n      code in item"))
    }

    @Test
    fun `containers carry across lines and allow lazy continuation`() {
        val cache = fullyTokenized(TestDoc("> - a\n> - b\nlazy\n\nout"))
        val lazy = cache.resultAt(2).endState
        assertEquals(2, lazy.containerDepth)
        assertEquals(1, lazy.quoteDepth)
        assertEquals(true, lazy.isQuote(0))
        assertEquals(4, lazy.listContentColumn(1))
        assertEquals(0, cache.resultAt(4).endState.containerDepth)
    }

    @Test
    fun `a fence inside a quote ends with the quote`() {
        assertEquals(listOf(CODE_FENCE, CODE, PARAGRAPH, HEADING), kinds("> ```\n> # code\nplain\n# heading"))
    }

    @Test
    fun `a fence inside a list item continues over blank lines`() {
        assertEquals(listOf(CODE_FENCE, CODE, CODE, CODE, CODE_FENCE), kinds("- ```\n  a\n\n  # b\n  ```"))
    }

    @Test
    fun `states compare by value`() {
        val tokenizer = LineTokenizer()
        val a = tokenizer.tokenize("```js", LineState.NORMAL).endState
        val b = LineTokenizer().tokenize("```js", LineState.NORMAL).endState
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, tokenizer.tokenize("```ts", LineState.NORMAL).endState)
        assertNotEquals(a, tokenizer.tokenize("~~~js", LineState.NORMAL).endState)
        assertEquals("LineState(FENCED_CODE fence=`x3 info=js code=javascript/0)", a.toString())
    }

    @Test
    fun `an ordered list may interrupt a paragraph only from 1`() {
        assertEquals(listOf(PARAGRAPH, PARAGRAPH), kinds("text\n2. not a list"))
        val cache = fullyTokenized(TestDoc("text\n1. a list"))
        assertEquals(1, cache.resultAt(1).endState.containerDepth)
    }
}
