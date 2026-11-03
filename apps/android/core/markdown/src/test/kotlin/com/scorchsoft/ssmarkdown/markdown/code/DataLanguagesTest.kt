package com.scorchsoft.ssmarkdown.markdown.code

import org.junit.Assert.assertEquals
import org.junit.Test

/** Markup, style sheets, configuration formats, diffs and Markdown inside a fence. */
class DataLanguagesTest {
    @Test
    fun `html tags, attributes, comments and declarations`() {
        val lines =
            highlight(
                "html",
                """
                <!DOCTYPE html>
                <a href="/x" class='y' disabled>Link</a> <!-- a
                comment --> <br/>
                <img src="a.png"
                     alt="multi
                line">
                """.trimIndent(),
            )
        assertEquals(listOf("META:<!DOCTYPE html>"), lines[0].runs)
        assertEquals(
            listOf(
                "TAG:<a",
                "ATTRIBUTE:href",
                "STRING:\"/x\"",
                "ATTRIBUTE:class",
                "STRING:'y'",
                "ATTRIBUTE:disabled",
                "TAG:>",
                "TAG:</a>",
                "COMMENT:<!-- a",
            ),
            lines[1].runs,
        )
        assertEquals(listOf("COMMENT:comment -->", "TAG:<br/>"), lines[2].runs)
        assertEquals(listOf("TAG:<img", "ATTRIBUTE:src", "STRING:\"a.png\""), lines[3].runs)
        assertEquals(listOf("ATTRIBUTE:alt", "STRING:\"multi"), lines[4].runs)
        assertEquals(listOf("STRING:line\"", "TAG:>"), lines[5].runs)
    }

    @Test
    fun `xml and svg use the markup lexer`() {
        assertEquals(
            listOf("META:<?xml version=\"1.0\"?>", "TAG:<svg:rect", "ATTRIBUTE:x", "STRING:\"1\"", "TAG:/>"),
            runs("xml", "<?xml version=\"1.0\"?><svg:rect x=\"1\"/>"),
        )
        assertEquals(listOf("TAG:<svg>"), runs("SVG", "<svg>"))
    }

    @Test
    fun `css selectors, properties, values and comments`() {
        val lines =
            highlight(
                "css",
                """
                @media (max-width: 600px) {
                  div.card > p:hover, #main {
                    color: #fff; margin: 0 1.5em !important;
                    background: url(img.png) rgb(0 0 0 / 50%); /* a
                  comment */ font-family: "Inter";
                  }
                }
                """.trimIndent(),
            )
        assertEquals(listOf("KEYWORD:@media", "NUMBER:600px"), lines[0].runs)
        assertEquals(listOf("KEYWORD:div", "KEYWORD:p"), lines[1].runs)
        assertEquals(
            listOf("ATTRIBUTE:color", "NUMBER:#fff", "ATTRIBUTE:margin", "NUMBER:0", "NUMBER:1.5em", "META:!important"),
            lines[2].runs,
        )
        assertEquals(
            listOf(
                "ATTRIBUTE:background",
                "BUILT_IN:url",
                "STRING:img.png",
                "BUILT_IN:rgb",
                "NUMBER:0",
                "NUMBER:0",
                "NUMBER:0",
                "NUMBER:50%",
                "COMMENT:/* a",
            ),
            lines[3].runs,
        )
        assertEquals(listOf("COMMENT:  comment */", "ATTRIBUTE:font-family", "STRING:\"Inter\""), lines[4].runs)
    }

    @Test
    fun `scss and less variables and line comments`() {
        assertEquals(listOf("VARIABLE:\$gap", "NUMBER:4px", "COMMENT:// c"), runs("scss", "\$gap: 4px; // c"))
        assertEquals(listOf("VARIABLE:@gap", "NUMBER:4px"), runs("less", "@gap: 4px;"))
        assertEquals(listOf("KEYWORD:@import", "STRING:\"a\""), runs("less", "@import \"a\";"))
    }

    @Test
    fun `yaml keys, values, comments, anchors and block scalars`() {
        val lines =
            highlight(
                "yml",
                """
                ---
                name: SS Markdown # app
                count: 3
                - enabled: yes
                  tags: [a, "b", 1, null]
                base: &base !!str value
                script: |
                  echo "not: a key"
                  # not a comment
                next: ~
                """.trimIndent(),
            )
        assertEquals(listOf("META:---"), lines[0].runs)
        assertEquals(listOf("ATTRIBUTE:name", "STRING:SS Markdown", "COMMENT:# app"), lines[1].runs)
        assertEquals(listOf("ATTRIBUTE:count", "NUMBER:3"), lines[2].runs)
        assertEquals(listOf("SYMBOL:-", "ATTRIBUTE:enabled", "LITERAL:yes"), lines[3].runs)
        assertEquals(listOf("ATTRIBUTE:tags", "STRING:\"b\"", "NUMBER:1", "LITERAL:null"), lines[4].runs)
        assertEquals(listOf("ATTRIBUTE:base", "TYPE:&base", "TYPE:!!str", "STRING:value"), lines[5].runs)
        assertEquals(listOf("ATTRIBUTE:script"), lines[6].runs)
        assertEquals(listOf("STRING:echo \"not: a key\""), lines[7].runs)
        assertEquals(listOf("STRING:# not a comment"), lines[8].runs)
        assertEquals(listOf("ATTRIBUTE:next", "LITERAL:~"), lines[9].runs)
    }

    @Test
    fun `toml sections, keys, values and multi-line strings`() {
        val lines =
            highlight(
                "toml",
                """
                [package] # c
                name = "ss" # name
                version = 1.2
                date = 2026-11-03T10:00:00Z
                tags = ["a", true, { x = 1 }]
                text = ""${'"'}
                multi # not a comment
                ""${'"'}
                """.trimIndent(),
            )
        assertEquals(listOf("SECTION:[package]", "COMMENT:# c"), lines[0].runs)
        assertEquals(listOf("ATTRIBUTE:name", "STRING:\"ss\"", "COMMENT:# name"), lines[1].runs)
        assertEquals(listOf("ATTRIBUTE:version", "NUMBER:1.2"), lines[2].runs)
        assertEquals(listOf("ATTRIBUTE:date", "NUMBER:2026-11-03T10:00:00Z"), lines[3].runs)
        assertEquals(listOf("ATTRIBUTE:tags", "STRING:\"a\"", "LITERAL:true", "ATTRIBUTE:x", "NUMBER:1"), lines[4].runs)
        assertEquals(listOf("ATTRIBUTE:text", "STRING:\"\"\""), lines[5].runs)
        assertEquals(listOf("STRING:multi # not a comment"), lines[6].runs)
        assertEquals(listOf("STRING:\"\"\""), lines[7].runs)
    }

    @Test
    fun `ini sections, keys, comments and literals`() {
        assertEquals(
            listOf(
                listOf("SECTION:[core]"),
                listOf("COMMENT:; note"),
                listOf("ATTRIBUTE:editor", "STRING:\"vim\""),
                listOf("ATTRIBUTE:bare", "LITERAL:true"),
                listOf("ATTRIBUTE:port", "NUMBER:8080"),
            ),
            highlight("ini", "[core]\n; note\neditor = \"vim\"\nbare: true\nport=8080").map { it.runs },
        )
    }

    @Test
    fun `diff lines`() {
        assertEquals(
            listOf(
                listOf("COMMENT:diff --git a/x b/x"),
                listOf("COMMENT:--- a/x"),
                listOf("COMMENT:+++ b/x"),
                listOf("META:@@ -1,2 +1,2 @@"),
                emptyList(),
                listOf("DELETION:-old"),
                listOf("ADDITION:+new"),
            ),
            highlight(
                "patch",
                "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n same\n-old\n+new",
            ).map { it.runs },
        )
    }

    @Test
    fun `markdown inside a fence is flat`() {
        val lines = highlight("````md", "# Title\n- item with `code` and **bold**\n> quote\n```js\nconst x = 1\n```")
        assertEquals(listOf("SECTION:# Title"), lines[0].runs)
        assertEquals(listOf("SYMBOL:-", "STRING:`code`"), lines[1].runs)
        assertEquals(listOf("COMMENT:> quote"), lines[2].runs)
        assertEquals(listOf("STRING:```js"), lines[3].runs)
        assertEquals("nothing nested", emptyList<String>(), lines[4].runs)
    }
}
