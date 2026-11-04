package com.scorchsoft.ssmarkdown.markdown.tidy

/** One hand-written case: [input] tidies to [output] (the same text when nothing may change). */
data class TidyCase(
    val name: String,
    val input: String,
    val output: String,
)

/**
 * The hand-written cases: one or more per rule, and one per thing Tidy
 * must not touch. `TidyVectorsTest` writes them, with generated documents,
 * to the vectors file that Preview renders before and after.
 */
object TidyCases {
    private fun same(
        name: String,
        text: String,
    ) = TidyCase(name, text, text)

    val all: List<TidyCase> =
        listOf(
            // ExtraBlankLines
            TidyCase("blank-run", "a\n\n\n\nb\n", "a\n\nb\n"),
            TidyCase("blank-run-whitespace", "a\n  \n\t\n \nb\n", "a\n\nb\n"),
            TidyCase("blank-leading", "\n\n\n# Title\n", "# Title\n"),
            TidyCase("blank-leading-before-dashes", "\n\n---\ntitle: x\n---\n", "\n---\ntitle: x\n---\n"),
            TidyCase("blank-in-list", "- a\n\n\n- b\n", "- a\n\n- b\n"),
            same("blank-quote-marker-is-not-blank", "> a\n>\n>\n> b\n"),
            same("blank-between-indented-code", "    code\n\n\n    more\n"),
            same("blank-before-definition", "Term\n\n\n: definition\n"),
            same("blank-in-fence", "```\na\n\n\n\nb\n```\n"),
            same("blank-in-math", "$$\na\n\n\nb\n$$\n"),
            same("blank-in-comment", "<!--\na\n\n\nb\n-->\n"),
            same("blank-in-pre", "<pre>\na\n\n\nb\n</pre>\n"),
            same("blank-in-list-fence", "- ```\n  a\n\n\n  b\n  ```\n"),
            TidyCase("blank-only-file", "\n\n  \n", ""),
            // TrailingSpaces
            TidyCase("trailing-spaces", "a  \n\nb \t\n", "a\n\nb\n"),
            TidyCase("trailing-single-space", "one \ntwo\n", "one\ntwo\n"),
            same("hard-break-kept", "one  \ntwo\n"),
            same("tab-mid-paragraph-kept", "one\t\ntwo\n"),
            same("hard-break-many-spaces", "one     \ntwo\n"),
            TidyCase("hard-break-at-paragraph-end", "one  \n\ntwo\n", "one\n\ntwo\n"),
            same("backslash-then-space", "one\\ \ntwo\n"),
            same("code-span-across-lines", "a `b \nc` d\n"),
            same("html-attribute-across-lines", "a <span title=\"x \ny\">z</span>\n"),
            TidyCase("trailing-heading", "# Title   \n", "# Title\n"),
            same("trailing-in-fence", "```\ncode   \n```\n"),
            same("trailing-indented-code", "    code   \n"),
            same("trailing-tab-indented", "\tcode   \n"),
            TidyCase("trailing-quote", "> a \n> b  \n> c\n", "> a\n> b  \n> c\n"),
            same("trailing-table", "| a |  \n| - |\n| 1 |  \n"),
            same("trailing-front-matter", "---\ntitle: x  \n---\n"),
            same("trailing-html", "<div>  \nx  \n</div>\n"),
            // FinalNewline
            TidyCase("final-newline-missing", "a", "a\n"),
            TidyCase("final-newline-extra", "a\n\n\n", "a\n"),
            TidyCase("final-newline-trailing-spaces", "a  ", "a\n"),
            same("empty-file", ""),
            same("final-newline-open-math", "$$\nx"),
            same("final-newline-open-comment", "<!-- a\n\n"),
            // UnclosedFence
            TidyCase("unclosed-fence", "```js\ncode\n", "```js\ncode\n```\n"),
            TidyCase("unclosed-fence-no-newline", "```\ncode", "```\ncode\n```\n"),
            TidyCase("unclosed-fence-long", "````\na\n```\nb\n", "````\na\n```\nb\n````\n"),
            TidyCase("unclosed-tilde-indented", "  ~~~\nx\n", "  ~~~\nx\n  ~~~\n"),
            TidyCase("unclosed-fence-trailing-blanks", "```\ncode\n\n\n", "```\ncode\n\n\n```\n"),
            same("unclosed-fence-in-list", "- ```\n  code\n"),
            // HeadingSpacing
            TidyCase("heading-spacing", "#   Title\n", "# Title\n"),
            TidyCase("heading-spacing-level", "###  Three  ###\n", "### Three  ###\n"),
            same("hashtag", "#tag and #another\n"),
            same("heading-tab", "#\tTitle\n"),
            same("heading-empty-closing", "#   #\n"),
            same("heading-in-list", "- #   item\n"),
            // BlankLineAroundHeading
            TidyCase("heading-after-text", "Para\n# H\nmore\n", "Para\n\n# H\n\nmore\n"),
            TidyCase("heading-after-list", "- a\n- b\n## H\n", "- a\n- b\n\n## H\n"),
            TidyCase("heading-before-list", "# H\n1. a\n2. b\n", "# H\n\n1. a\n2. b\n"),
            TidyCase("heading-quote", "> q\n# H\n> r\n", "> q\n\n# H\n\n> r\n"),
            TidyCase("heading-heading", "# A\n## B\n", "# A\n\n## B\n"),
            TidyCase("heading-after-text-hard-break", "Para  \n# H\n", "Para\n\n# H\n"),
            same("table-then-heading", "| a | b |\n|---|---|\n| 1 | 2 |\n# H\n"),
            same("heading-after-html", "<div>\n# H\n</div>\n"),
            same("heading-after-setext", "Title\n=====\n# H\n"),
            same("heading-after-front-matter", "---\nx: 1\n---\n# H\n"),
            same("heading-indented", "Para\n  # H\n"),
            same("heading-before-indented", "# H\n    code\n"),
            same("heading-before-definition", "# H\n: not a term\n"),
            same("setext-dashes", "para\n---\n"),
            // BlankLineAroundFence
            TidyCase("fence-after-text", "Para\n```\ncode\n```\nafter\n", "Para\n\n```\ncode\n```\n\nafter\n"),
            TidyCase("fence-heading", "# H\n```\nx\n```\n", "# H\n\n```\nx\n```\n"),
            TidyCase("fence-fence", "```\na\n```\n~~~\nb\n~~~\n", "```\na\n```\n\n~~~\nb\n~~~\n"),
            same("fence-in-list-item", "- item\n  ```\n  code\n  ```\n- next\n"),
            same("fence-in-quote", "> a\n> ```\n> x\n> ```\n"),
            same("fence-after-table", "| a |\n| - |\n```\nx\n```\n"),
            same("fence-indented", "Para\n  ```\n  x\n  ```\n"),
            same("fence-backtick-info", "``` a`b\nnot a fence\n"),
            // ListNumbering
            TidyCase("list-renumber", "1. a\n1. b\n1. c\n", "1. a\n2. b\n3. c\n"),
            TidyCase("list-renumber-start", "3) a\n7) b\n", "3) a\n4) b\n"),
            TidyCase("list-renumber-loose", "1. a\n\n5. b\n\n\n9. c\n", "1. a\n\n2. b\n\n3. c\n"),
            TidyCase(
                "list-renumber-nested",
                "1. a\n   1. x\n   5. y\n2. b\n9. c\n",
                "1. a\n   1. x\n   2. y\n2. b\n3. c\n",
            ),
            TidyCase("list-renumber-continuation", "1. a\n\n   more\n4. b\n", "1. a\n\n   more\n2. b\n"),
            TidyCase("list-renumber-lazy", "1. wraps\nlazily\n3. b\n", "1. wraps\nlazily\n2. b\n"),
            TidyCase("list-renumber-wider", "9. a\n1. b\n", "9. a\n10. b\n"),
            same("list-wider-with-continuation", "9. a\n1. b\n   more\n"),
            same("list-delimiters-differ", "1. a\n3) b\n"),
            same("list-1984", "Text\n1984. was a year\n1985. too\n"),
            same("list-not-interrupting", "Text\n2. b\n5. c\n"),
            same("list-nested-not-interrupting", "- a\n  2. b\n  5. c\n"),
            same("list-split-by-paragraph", "1. a\n\npara\n\n5. b\n"),
            same("list-with-code", "1. a\n\n       code\n5. b\n"),
            same("list-with-fence", "1. a\n   ```\n   2. x\n   ```\n5. b\n"),
            same("list-in-quote", "> 1. a\n> 5. b\n"),
            same("list-bullets-kept", "- a\n* b\n+ c\n"),
            TidyCase("list-bullet-then-ordered", "- a\n\n2. x\n9. y\n", "- a\n\n2. x\n3. y\n"),
            // Must not change
            same("dash-item", "-item\n#Heading\n"),
            same("emphasis-unbalanced", "**bold _it\n"),
            same("html-run-with-heading", "<div>\n## Inside\ntext  \n</div>\n"),
            same("comment-with-blank-lines", "<!-- a\n\n\n# x  \n-->\n"),
            same("script", "<script>\nlet a = 1;  \n\n\nlet b = 2;\n</script>\n"),
            same("front-matter-only", "---\ntitle: x\n# not a heading\n"),
            same("crlf-untouched", "a  \r\n\r\n\r\n\r\nb\r\n"),
            same("tidy-already", "# Title\n\nSome text.\n\n- a\n- b\n"),
            same("deflist", "Term\n: one  \n: two\n"),
            same("footnote", "Text[^1].\n\n[^1]: Note\n\n    more  \n"),
        )
}
