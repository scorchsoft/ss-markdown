package com.scorchsoft.ssmarkdown.markdown.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The paste conversions shared with Preview: `paste-conversions.txt`, which
 * the page's `pasteConversions.test.ts` runs through the browser's own HTML
 * parser. Kotlin's tolerant parser and walker must give every case exactly:
 * Gmail, Google Docs, Word and web-page HTML to Markdown, and Markdown to
 * the words "Paste as plain text" inserts. The page's test regenerates the
 * file (`UPDATE_VECTORS=1`); this one only reads it.
 */
class PasteConversionsTest {
    private class Case(
        val name: String,
        val sections: Map<String, String>,
    )

    private val cases: List<Case> by lazy { parse(VECTORS.readText()) }

    @Test
    fun `every shared case converts exactly as in Preview`() {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val html = case.sections["html"]
            val plain = case.sections["plain"]
            if (html != null) {
                val markdown = PasteClip.content(plain, html).text
                if (markdown != case.sections["markdown"]) {
                    failures += "${case.name} markdown:\n$markdown\n--- want\n${case.sections["markdown"]}"
                }
            }
            val words = if (html != null) PasteClip.words(plain, html) else MarkdownPlainText.of(plain.orEmpty())
            if (words !=
                case.sections["words"]
            ) {
                failures += "${case.name} words:\n$words\n--- want\n${case.sections["words"]}"
            }
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
        assertTrue(cases.size >= MIN_CASES)
    }

    @Test
    fun `the HTML says whether it was formatting, so a plain wrapper pastes the plain text`() {
        assertFalse(PasteClip.htmlToMarkdown("<p>plain</p><p>text</p>").formatted)
        assertFalse(PasteClip.htmlToMarkdown("<p>a<br>b</p>").formatted)
        assertTrue(PasteClip.htmlToMarkdown("<p><b>b</b></p>").formatted)
        val wrapped = PasteClip.content("Just *words*", "<span>Just *words*</span>")
        assertEquals("Just *words*", wrapped.text)
        assertFalse(wrapped.fromHtml)
        assertTrue(PasteClip.content("Hi there", "<p>Hi <b>there</b></p>").fromHtml)
        assertEquals("", PasteClip.content(null, "<img src='data:x'>").text)
    }

    @Test
    fun `no address but the web and mail is ever written`() {
        for (href in listOf("javascript:alert(1)", "JaVaScRiPt:x", "data:text/html,x", "vbscript:x", "file:///etc")) {
            assertEquals("t", PasteClip.htmlToMarkdown("<a href=\"$href\">t</a>").markdown)
        }
        assertEquals("", PasteClip.htmlToMarkdown("<img src=\"javascript:x\" alt=\"a\">").markdown)
        assertEquals(
            "ae",
            PasteClip
                .htmlToMarkdown(
                    "<p onmouseover=\"x()\">a<script>b</script><style>c</style><!--d-->e</p>",
                ).markdown,
        )
    }

    @Test
    fun `a clip is rich when it has HTML or Markdown`() {
        assertTrue(PasteClip.isRich(null, "<b>x</b>"))
        assertTrue(PasteClip.isRich("# Title", null))
        assertTrue(PasteClip.isRich("| a | b |\n| - | - |", null))
        assertFalse(PasteClip.isRich("snake_case and a * b, $5, 10:30", null))
        assertFalse(PasteClip.isRich("Two\n\n\nparagraphs", ""))
        assertFalse(PasteClip.isRich(null, null))
    }

    @Test
    fun `broken and hostile HTML never throws`() {
        val hostile =
            listOf(
                "<",
                "<<<>>>",
                "<p",
                "<a href='x",
                "</div></p></li>",
                "<!--",
                "<![CDATA[x]]>",
                "&#xFFFFFF; &#0; &#55296;",
                "<table><td>loose</td></table>",
                "<li>stray item</li>",
                "<ul><ul><li>deep</li></ul></ul>",
                "<pre>\n\n</pre>",
                "<code></code>",
                "<h7>x</h7>",
                "<b><i>unclosed",
                "<div>".repeat(1_000) + "deep" + "</div>".repeat(1_000),
                "<script>never closed",
                "<p>a</b>b</p>",
            )
        for (html in hostile) PasteClip.content(null, html)
        assertEquals("deep", PasteClip.content(null, "<div>".repeat(1_000) + "deep").text)
        assertEquals("- stray item", PasteClip.content(null, "<li>stray item</li>").text)
        assertEquals("loose", PasteClip.content(null, "<table><td>loose</td></table>").text.trim('|', ' ', '\n', '-'))
    }

    @Test
    fun `character references decode, unknown ones stay`() {
        assertEquals("&AB�&nope;", HtmlEntities.decode("&amp;&#x41;&#66;&#0;&nope;"))
    }

    @Test
    fun `text escapes only where syntax could act`() {
        assertEquals(
            "snake_case a * b \\*x\\* \\[y\\] \\<b> $5 \\\$x$ \\=\\=m\\=\\= \\:smile:",
            MarkdownText.escapeInline("snake_case a * b *x* [y] <b> $5 \$x$ ==m== :smile:"),
        )
        assertEquals("\\# h", MarkdownText.escapeLineStart("# h"))
        assertEquals("12\\) x", MarkdownText.escapeLineStart("12) x"))
    }

    @Test
    fun `unclosed code and links stay as written`() {
        assertEquals("a `b and [c](d", MarkdownPlainText.of("a `b and [c](d"))
        assertEquals("x", MarkdownPlainText.of("```\nx"))
        assertEquals("a\tb", MarkdownPlainText.of("a | b\n--- | ---"))
    }

    private fun parse(text: String): List<Case> {
        val cases = mutableListOf<Pair<String, MutableMap<String, String>>>()
        var section: String? = null
        val lines = mutableListOf<String>()

        fun end() {
            val current = cases.lastOrNull()
            if (current != null && section != null) current.second[section!!] = lines.joinToString("\n")
            lines.clear()
        }
        for (line in text.removeSuffix("\n").split("\n")) {
            val caseHead = Regex("^=== (\\S+)$").find(line)
            val sectionHead = Regex("^--- (html|plain|markdown|words)$").find(line)
            when {
                caseHead != null -> {
                    end()
                    section = null
                    cases += caseHead.groupValues[1] to mutableMapOf()
                }

                sectionHead != null -> {
                    end()
                    section = sectionHead.groupValues[1]
                }

                else -> {
                    lines += line
                }
            }
        }
        end()
        return cases.map { Case(it.first, it.second) }
    }

    private companion object {
        val VECTORS = File("../../feature/preview/web/test/vectors/paste-conversions.txt")
        const val MIN_CASES = 20
    }
}
