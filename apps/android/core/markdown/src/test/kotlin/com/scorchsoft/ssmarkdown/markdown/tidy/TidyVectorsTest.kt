package com.scorchsoft.ssmarkdown.markdown.tidy

import com.scorchsoft.ssmarkdown.markdown.MalformedMarkdownTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Tidy over everything we have: the hand cases, a few hundred generated
 * documents, the malformed-Markdown cases and the repository's own docs.
 * Each must never throw, pass the safety check, tidy in bounded time and
 * be tidy afterwards.
 *
 * The hand and generated cases are also written, input and output, to
 * Preview's `test/vectors/tidy-cases.txt`, which `tidyRender.test.ts`
 * renders with the real markdown-it set-up to prove the two render the
 * same. After an intended change, regenerate it with
 * `gradle -p apps/android :core:markdown:test -Pssmarkdown.updateVectors=true --tests '*TidyVectorsTest*'`,
 * review the diff, then run the web test.
 */
class TidyVectorsTest {
    private fun check(
        name: String,
        text: String,
    ) {
        val started = System.nanoTime()
        val raw = MarkdownTidy.unguarded(text)
        assertNotNull("$name: overlapping edits", raw)
        val (plan, fence) = raw!!
        if (!text.contains('\r')) {
            assertTrue("$name: guard fails", MarkdownTidy.isFaithful(text, plan.applyTo(text), fence))
        }
        val out = MarkdownTidy.plan(text).applyTo(text)
        assertTrue(
            "$name: not idempotent\n$text\n----\n$out\n----\n${MarkdownTidy.plan(out).applyTo(out)}",
            MarkdownTidy.plan(out).isEmpty,
        )
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("$name: $millis ms", millis < MAX_MILLIS)
    }

    @Test
    fun `generated documents pass the guard and are idempotent`() {
        for ((name, text) in generated()) check(name, text)
    }

    @Test
    fun `the malformed cases never throw and stay faithful`() {
        for ((name, text) in MalformedMarkdownTest().cases) check(name, text)
    }

    @Test
    fun `the repository's docs tidy faithfully`() {
        val docs = docs()
        assertTrue(docs.size >= MIN_DOCS)
        for (doc in docs) check(doc.path, doc.readText())
    }

    private fun docs(): List<File> =
        File(REPO, "docs").walkTopDown().filter { it.isFile && it.extension == "md" }.toList()

    @Test
    fun `the shared vectors are what Kotlin tidies`() {
        val cases = TidyCases.all.map { it.name to it.input } + generated()
        val expected =
            write(
                cases.map { (name, input) ->
                    Triple(name, input, MarkdownTidy.plan(input).applyTo(input))
                },
            )
        if (System.getProperty("ssmarkdown.updateVectors") == "true") VECTORS.writeText(expected)
        val actual = VECTORS.readText()
        if (actual != expected) {
            val want = expected.split("\n=== ")
            val have = actual.split("\n=== ")
            val first = want.indices.firstOrNull { it >= have.size || want[it] != have[it] } ?: want.size
            assertEquals(
                "first different case (regenerate with -Pssmarkdown.updateVectors=true)",
                want.getOrNull(first),
                have.getOrNull(first),
            )
        }
        assertTrue(cases.size >= MIN_CASES)
    }

    /** `=== name`, then `--- input` and `--- output`, each line ending in `¶` so trailing spaces survive editors. */
    private fun write(cases: List<Triple<String, String, String>>): String =
        cases.joinToString("") { (name, input, output) ->
            "=== $name\n--- input\n${lines(input)}\n--- output\n${lines(output)}\n"
        }

    private fun lines(text: String) = text.split("\n").joinToString("\n") { "$it¶" }

    @Test
    fun `a large fuzz run can be written for Preview to render`() {
        // `-Pssmarkdown.tidyFuzz=<path>` writes thousands of generated cases for `TIDY_VECTORS=<path>`.
        val path = System.getProperty("ssmarkdown.tidyFuzz").orEmpty()
        if (path.isEmpty()) return
        val seed = System.getProperty("ssmarkdown.tidyFuzzSeed")?.toIntOrNull() ?: FUZZ_SEED
        val random = Random(seed)
        val docs = docs().flatMap { listOf("doc-" + it.name.removeSuffix(".md") to it.readText()) }
        val mutated = docs.map { (name, text) -> "$name-mutated" to mutate(text, random) }
        val cases = generated(seed, FUZZ_COUNT) + docs + mutated
        for ((name, text) in cases) check(name, text)
        File(path).writeText(
            write(
                cases.map { (name, input) ->
                    Triple(name, input, MarkdownTidy.plan(input).applyTo(input))
                },
            ),
        )
    }

    /**
     * Seeded documents, the same every run: half made of whole block
     * snippets joined by random gaps, half a soup of single lines (markers,
     * fences, indents, tags, quotes, blanks) that make odd structures.
     */
    private fun generated(
        seed: Int = SEED,
        count: Int = GENERATED,
    ): List<Pair<String, String>> {
        val random = Random(seed)
        return (0 until count).map { n ->
            if (n % 2 == 1) return@map "generated-$n" to soup(random)
            val doc = StringBuilder()
            if (random.nextInt(10) == 0) doc.append("---\ntitle: Doc $n\n---\n")
            repeat(2 + random.nextInt(7)) {
                doc.append(SNIPPETS[random.nextInt(SNIPPETS.size)])
                doc.append(GAPS[random.nextInt(GAPS.size)])
            }
            if (random.nextInt(4) == 0) doc.append(ENDINGS[random.nextInt(ENDINGS.size)])
            "generated-$n" to doc.toString()
        }
    }

    /** A real document made untidy: trailing spaces, doubled blank lines, spaced headings, lists all `1.`. */
    private fun mutate(
        text: String,
        random: Random,
    ): String =
        text.split("\n").joinToString("\n") { line ->
            val roll = random.nextInt(12)
            when {
                line.isEmpty() && roll < 3 -> "\n".repeat(roll + 1)
                line.startsWith("#") && roll < 6 -> line.replaceFirst(" ", "   ")
                roll == 0 -> line.replaceFirst(Regex("^(\\s*)\\d+([.)]) "), "$11$2 ")
                roll == 1 -> "$line  "
                roll == 2 -> "$line \t"
                roll == 3 -> "$line "
                else -> line
            }
        }

    private fun soup(random: Random): String {
        val doc = StringBuilder()
        repeat(3 + random.nextInt(28)) {
            val indent = INDENTS[random.nextInt(INDENTS.size)]
            doc.append(indent).append(LINES[random.nextInt(LINES.size)])
            if (random.nextInt(5) == 0) doc.append(TAILS[random.nextInt(TAILS.size)])
            doc.append('\n')
        }
        if (random.nextInt(3) == 0) doc.setLength(doc.length - 1)
        return doc.toString()
    }

    private companion object {
        val REPO = File("../../../..")
        val VECTORS = File("../../feature/preview/web/test/vectors/tidy-cases.txt")
        const val SEED = 20261104
        const val GENERATED = 400
        const val MIN_CASES = 400
        const val MIN_DOCS = 10
        const val MAX_MILLIS = 3_000

        const val FUZZ_SEED = 7
        const val FUZZ_COUNT = 6_000

        val INDENTS = listOf("", "", "", "", " ", "  ", "   ", "    ", "      ", "\t", "> ", "> > ", ">")
        val TAILS = listOf(" ", "  ", "\t", "   ", " \\")
        val LINES =
            listOf(
                "",
                "",
                "",
                "text",
                "more text",
                "wrapped line",
                "# Heading",
                "##    Spaced",
                "#tag",
                "1. one",
                "1. ",
                "2. two",
                "7) seven",
                "10. ten",
                "- dash",
                "* star",
                "+ plus",
                "-",
                "```",
                "```js",
                "~~~",
                "````",
                "$$",
                "$$ x $$",
                "<div>",
                "</div>",
                "<span>",
                "<!--",
                "-->",
                "<pre>",
                "</pre>",
                "| a | b |",
                "|---|---|",
                "---",
                "===",
                "***",
                "> quote",
                ": definition",
                "~ tilde def",
                "[ref]: /u",
                "[^1]: note",
                "`code",
                "span`",
                "  - nested",
                "   1. nested",
                "<b>bold</b> text",
                "- ```",
                "1. ```",
                "- > q",
                "3. three",
                "5. five",
                "    ```",
                "<!-- x -->",
                "Term",
                "`` a ` b ``",
                "a \\` b",
                "*[HTML]: Hyper",
                "\\",
                "##",
            )

        val GAPS = listOf("\n", "\n", "\n\n", "\n\n", "\n\n\n", "\n  \n\n", "\n\n\n\n")
        val ENDINGS = listOf("", "\n\n", "```\nopen fence  \n", "    ", "text without newline")

        val SNIPPETS =
            listOf(
                "# Title",
                "##   Spaced heading",
                "### Closed ###",
                "#tag line",
                "#",
                "Plain paragraph text.",
                "Two lines  \nwith a hard break",
                "Trailing space \nthen more",
                "Trailing tab\t",
                "Ends with spaces   ",
                "a `code \nspan` b",
                "a [link](/u \"title \nmore\") b",
                "Term\n: definition",
                "Term\n\n: loose definition",
                "Setext\n===",
                "Setext two\n---",
                "***",
                "- - -",
                "1. one\n1. two\n1. three",
                "3. three\n\n7. seven\n\n\n9. nine",
                "- a\n  1. x\n  5. y\n- b",
                "1. wraps\nlazily\n4. next",
                "9. nine\n3. ten",
                "8. a\n9. b\n3. c\n   more",
                "- a\n\n\n- b",
                "* star\n+ plus\n- dash",
                "1) paren\n5) paren",
                "Text\n1984. was a year",
                "Text\n1. interrupts",
                "- item\n  ```\n  code  \n\n\n  ```\n- next",
                "- a\n\n      indented code  \n\n\n      more",
                "```\ncode  \n\n\nmore\n```",
                "~~~py\nx = 1\n~~~",
                "````\na\n```\nb\n````",
                "``` a`b\nnot a fence",
                "    indented code  \n\n\n    more code",
                "| a | b |\n|---|---|\n| 1 | 2 |  ",
                "| a |\n| - |",
                "a | b\n--- | ---",
                "> quote  \n> more",
                "> # quoted heading\n> - item",
                "> lazy\ncontinuation",
                "> ```\n> code\n> ```",
                "<div>\n# in html  \n</div>",
                "<!-- comment\n\n\nstill comment -->",
                "<span>inline</span> text",
                "<details>\n<summary>S</summary>\n\nbody\n\n</details>",
                "$$\nx^2  \n\n\ny\n$$",
                "$$ a $$",
                "[^1]: Footnote\n\n    more  ",
                "Cite[^1] here.",
                "[ref]: /url 'title'",
                "*[HTML]: Hyper Text",
                "**bold _it",
                "-item",
                "   ",
                "1.\n2.",
                "- [ ] task\n- [x] done",
            )
    }
}
