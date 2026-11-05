package com.scorchsoft.ssmarkdown.markdown.tidy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTidyTest {
    private fun tidy(text: String): String = MarkdownTidy.plan(text).applyTo(text)

    @Test
    fun `every hand case tidies as written and is idempotent`() {
        val failures = mutableListOf<String>()
        for (case in TidyCases.all) {
            val out = tidy(case.input)
            if (out != case.output) failures += "${case.name}:\n${show(out)}\n--- want\n${show(case.output)}"
            if (!MarkdownTidy.plan(out).isEmpty) failures += "${case.name}: not idempotent"
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    private fun show(text: String) = text.replace(" ", "·").replace("\t", "→")

    @Test
    fun `counts say how many places each rule fixed`() {
        val plan = MarkdownTidy.plan("#   A\ntext  \n\n\n\n1. a\n1. b\n1. c\n```\ncode")
        assertEquals(
            mapOf(
                TidyRule.HeadingSpacing to 1,
                TidyRule.BlankLineAroundHeading to 1,
                TidyRule.TrailingSpaces to 1,
                TidyRule.ExtraBlankLines to 1,
                TidyRule.ListNumbering to 2,
                TidyRule.BlankLineAroundFence to 1,
                TidyRule.FinalNewline to 1,
                TidyRule.UnclosedFence to 1,
            ),
            plan.counts,
        )
        assertEquals(
            "# A\n\ntext\n\n1. a\n2. b\n3. c\n\n```\ncode\n```\n",
            plan.applyTo("#   A\ntext  \n\n\n\n1. a\n1. b\n1. c\n```\ncode"),
        )
    }

    @Test
    fun `edits are small, sorted and do not overlap`() {
        val text = "# A\n\n\n\nsome text \nmore\n\n\n\n1. x\n1. y\n"
        val plan = MarkdownTidy.plan(text)
        assertTrue(plan.edits.size >= 3)
        plan.edits.zipWithNext().forEach { (a, b) -> assertTrue(a.end < b.start) }
        plan.edits.forEach { assertTrue(it.end - it.start < text.length / 2) }
        assertEquals("# A\n\nsome text\nmore\n\n1. x\n2. y\n", plan.applyTo(text))
    }

    @Test
    fun `a tidy document has an empty plan`() {
        assertTrue(MarkdownTidy.plan("# Title\n\nText.\n").isEmpty)
        assertTrue(MarkdownTidy.plan("").isEmpty)
        assertEquals("x", TidyPlan.EMPTY.applyTo("x"))
    }

    @Test
    fun `files with carriage returns are left alone`() {
        assertTrue(MarkdownTidy.plan("a  \r\n\r\n\r\nb").isEmpty)
        assertTrue(MarkdownTidy.plan("a\rb\n\n\n").isEmpty)
    }

    @Test
    fun `the guard refuses a plan that changes a line`() {
        assertTrue(MarkdownTidy.isFaithful("# A\n1. x\n", "#  A\n\n\n7. x  \n", null))
        assertFalse(MarkdownTidy.isFaithful("a\n", "b\n", null))
        assertFalse(MarkdownTidy.isFaithful("a\n", "a\nb\n", null))
        assertTrue(MarkdownTidy.isFaithful("```\na\n", "```\na\n```\n", "```"))
    }

    @Test
    fun `tabs, deep nesting and odd markers are left alone`() {
        for (text in listOf("\t- a\n\t- b\n", "- a\n\t1. x\n\t5. y\n", "1. a\n    - b\n", "1.\ta\n2.\tb\n")) {
            assertEquals(text, tidy(text))
        }
    }

    @Test
    fun `unsure structures freeze until the top level is certain again`() {
        // A quote's heading then a numbered item: lazy or a new list? Frozen, then tidied after the blank line.
        val text = "> # h\n2. x\n9. y\n\n\n\nafter   \n"
        assertEquals("> # h\n2. x\n9. y\n\n\n\nafter\n", tidy(text))
        // With a fence in the unsure stretch, everything after it is frozen.
        val fenced = "> # h\n2. x\n   ```\n   a\nb\n```\n\n\n\nz  \n"
        assertEquals(fenced, tidy(fenced))
    }

    @Test
    fun `a definition, footnote or reference above keeps the paragraph unsure`() {
        assertEquals("[a]: /u\n2. x\n9. y\n", tidy("[a]: /u\n2. x\n9. y\n"))
        assertEquals("[a]: /u\n===\n", tidy("[a]: /u\n===\n"))
        assertEquals("text\n<span>\n", tidy("text\n<span>\n"))
    }

    @Test
    fun `html blocks of every kind are left alone`() {
        for (
        text in listOf(
            "<?php\n\n\necho 1;  \n?>\n",
            "<!DOCTYPE html>\n",
            "<![CDATA[\n\n\nx  \n]]>\n",
            "<custom-tag>\n\n\n",
            "<details>\n<summary>S</summary>  \n\n\n</details>\n",
            "<" + "a".repeat(5_000) + "\n",
        )
        ) {
            val out = tidy(text)
            assertTrue(text, out.startsWith(text.substringBefore("\n\n\n")))
        }
        assertEquals("<custom-tag>\n", tidy("<custom-tag>\n\n\n"))
    }

    @Test
    fun `pathological input is fast and never throws`() {
        val inputs =
            listOf(
                "word ".repeat(40_000) + "  \n\n\n",
                (0 until 10_000).joinToString("\n") { "${it % 7}. item $it  " },
                (0 until 10_000).joinToString("\n") { if (it % 3 == 0) "" else "line $it " },
                "```\n".repeat(5_000),
                "<div>\n".repeat(5_000),
                "> ".repeat(2_000) + "deep",
                (0 until 300).joinToString("\n") { "${"  ".repeat(it)}- level $it" },
                "<a " + "x=\"1\" ".repeat(20_000) + ">",
                "|".repeat(50_000) + "\n" + "-|".repeat(25_000),
            )
        for (text in inputs) {
            val started = System.nanoTime()
            val out = tidy(text)
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $millis ms", millis < 2_000)
            assertTrue(MarkdownTidy.plan(out).isEmpty)
        }
    }
}
