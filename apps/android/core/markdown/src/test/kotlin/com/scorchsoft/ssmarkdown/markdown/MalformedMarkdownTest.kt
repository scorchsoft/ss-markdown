package com.scorchsoft.ssmarkdown.markdown

import com.scorchsoft.ssmarkdown.markdown.edit.BlockType
import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand
import com.scorchsoft.ssmarkdown.markdown.edit.FenceStates
import com.scorchsoft.ssmarkdown.markdown.edit.InlineFormat
import com.scorchsoft.ssmarkdown.markdown.edit.ListKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.markdown.edit.assertInvariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Malformed Markdown in the Code view (Andrew, 2026-11-04: "files that have
 * invalid syntax"): unclosed and unbalanced constructs, ragged tables,
 * pathological runs and nesting, and characters that are not text. The
 * same cases as Preview's `e2e/malformed.test.ts`.
 *
 * - The tokenizer reads each in bounded time, and the incremental cache
 *   gives exactly what a scratch tokenise gives.
 * - Every edit command, at carets all over each document, keeps the edit
 *   invariants and never throws.
 */
class MalformedMarkdownTest {
    private fun repeat(
        s: String,
        n: Int,
    ): String = s.repeat(n)

    internal val cases: List<Pair<String, String>> =
        listOf(
            "unclosed emphasis" to "**bold _it ~~strike `code",
            "ten thousand stars" to repeat("*", 10_000),
            "alternating delimiters" to repeat("*_", 5_000),
            "unclosed brackets" to repeat("[", 5_000) + " text " + repeat("](", 2_000),
            "nested links" to repeat("[a", 500) + repeat("](b)", 500),
            "backtick runs of every length" to (1..200).joinToString(" x ") { "`".repeat(it) },
            "unclosed angle brackets" to repeat("<a ", 3_000),
            "entity soup" to repeat("&#x; &#; &amp &; &#99999999; ", 500),
            "deep quotes" to repeat(">", 1_000) + " deep",
            "deep lists" to (0 until 300).joinToString("\n") { "${"  ".repeat(it)}- level $it" },
            "deep mixed nesting" to
                (0 until 200).joinToString("\n") { "${"> ".repeat(it % 20)}${"  ".repeat(it % 7)}- $it" },
            "ragged table" to "| a | b |\n|---|\n| 1 | 2 | 3 | 4 | 5 |\n| x\n|\n||||\n| --- |",
            "huge table row" to "| ${repeat("c | ", 2_000)}\n| ${repeat("--- | ", 2_000)}\n| ${repeat("1 | ", 2_000)}",
            "a 200 KB line" to repeat("word ", 40_000),
            "lone CRs and NULs" to "one\rtwo\r\rthree\u0000four\u0000",
            "a byte-order mark mid-file" to "beforeafter\n\n﻿# heading",
            "lone surrogates" to "high \uD83D alone, low \uDE00 alone, swapped \uDE00\uD83D",
            "control characters" to (1..31).joinToString("x") { it.toChar().toString() },
            "combining marks" to "e" + repeat("́", 2_000) + " zalgo",
            "tabs everywhere" to "\t-\titem\n\t\t>\tquote\n\t```\n\tcode",
            "fence closed by a shorter fence" to "````\ncode\n```\nstill code\n````",
            "fence with a backtick info string" to "``` a`b\nnot a fence",
            "fence that never closes" to "```kotlin\nfun f() {\n  \"unclosed\n",
            "HTML block that never closes" to "<div>\n<span>\n<table><tr><td>cell",
            "comment that never closes" to "<!-- open\n\n- not a list\n",
            "front matter that never closes" to "---\ntitle: x\n# not a heading",
            "maths that never closes" to "$\$\nx^2\n\n\$x and \\(z",
            "footnotes gone wrong" to "[^a] [^] [^ b]\n\n[^a]:\n[^a]: twice\n[^]: empty",
            "reference definitions gone wrong" to "[a]: \n[b]: <unclosed\n[c]: /u 'bad\n[a][] [b] [c]",
            "headings gone wrong" to "#\n####### seven\n#no space\n# trailing #####\n=\n--",
            "list markers gone wrong" to "-\n1.\n- [ ]\n- [x\n10000000000. big\n1)\n* \n+\t",
        ).map { (name, text) -> name to "$text\n\nSentinel paragraph at the end." }

    private val commands: List<EditCommand> =
        InlineFormat.entries.map { EditCommand.ToggleInline(it) } +
            ListKind.entries.map { EditCommand.ToggleList(it) } +
            listOf(
                EditCommand.SetHeading(2),
                EditCommand.SetBlockType(BlockType.Paragraph),
                EditCommand.SetBlockType(BlockType.Task),
                EditCommand.SetBlockType(BlockType.CodeBlock),
                EditCommand.CycleHeading,
                EditCommand.ToggleBlockquote,
                EditCommand.ToggleCodeBlock,
                EditCommand.InsertLink,
                EditCommand.InsertTable(),
                EditCommand.Indent,
                EditCommand.Outdent,
                EditCommand.Newline,
                EditCommand.BackspaceAtItemStart,
                EditCommand.Type("x"),
                EditCommand.Type("}"),
                EditCommand.Type("(", smartPairs = true),
            )

    @Test
    fun `the tokenizer reads every malformed case quickly and incrementally equals scratch`() {
        for ((name, text) in cases) {
            val started = System.nanoTime()
            val doc = TestDoc(text)
            val cache = IncrementalTokenizer(doc)
            cache.continueInIdle(Long.MAX_VALUE / 4)
            assertTrue(name, cache.isComplete)
            val millis = (System.nanoTime() - started) / 1_000_000
            // Generous: a backtracking blow-up takes seconds to minutes, a normal read milliseconds.
            assertTrue("$name took $millis ms", millis < BUDGET_MILLIS)
            val scratch = tokenizeFromScratch(doc.lines)
            for (line in scratch.indices) {
                if (!cache.resultAt(line).sameAs(scratch[line])) fail("$name: line $line differs from scratch")
            }
            val last = doc.lineCount - 1
            // An unclosed fence, HTML block, comment or maths block takes the rest, as CommonMark says.
            if (name.endsWith("never closes")) continue
            assertEquals("$name: the sentinel is a paragraph", LineKind.PARAGRAPH, cache.resultAt(last).kind)
        }
    }

    @Test
    fun `an edit at the end of each malformed case is re-read like a scratch tokenise`() {
        for ((name, text) in cases) {
            val doc = TestDoc(text)
            val cache = IncrementalTokenizer(doc)
            cache.continueInIdle(Long.MAX_VALUE / 4)
            // Close or open things at the start, then type at the end.
            doc.edit(cache, 0, 0, "```\n")
            cache.continueInIdle(Long.MAX_VALUE / 4)
            doc.edit(cache, 0, 4, "")
            doc.edit(cache, doc.text.length, doc.text.length, "!")
            cache.continueInIdle(Long.MAX_VALUE / 4)
            val scratch = tokenizeFromScratch(doc.lines)
            for (line in scratch.indices) {
                if (!cache.resultAt(line).sameAs(scratch[line])) fail("$name: line $line differs after edits")
            }
        }
    }

    @Test
    fun `every edit command keeps its invariants on every malformed case`() {
        for ((name, text) in cases) {
            val fences = FenceStates.scan(text)
            val style = DocumentStyle.detect(text)
            val selections = carets(text).flatMap { listOf(TextSelection.caret(it), TextSelection(0, it)) }
            for (selection in selections) {
                for (command in commands) assertCommand(name, text, command, selection, style, fences)
            }
        }
    }

    @Suppress("LongParameterList") // One case of the matrix: every axis is a parameter.
    private fun assertCommand(
        name: String,
        text: String,
        command: EditCommand,
        selection: TextSelection,
        style: DocumentStyle,
        fences: FenceStates,
    ) {
        val result =
            try {
                command.apply(text, selection, style, fences)
            } catch (e: RuntimeException) {
                throw AssertionError("$command threw on \"$name\" at $selection", e)
            } catch (e: StackOverflowError) {
                throw AssertionError("$command overflowed the stack on \"$name\" at $selection", e)
            }
        assertInvariants(text, result, result.applyTo(text))
    }

    /** Line starts, line ends and a few points between, capped so a 200 KB line stays quick. */
    private fun carets(text: String): List<Int> {
        val out = sortedSetOf(0, text.length)
        var start = 0
        while (start <= text.length && out.size < MAX_CARETS) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            out += start
            out += end
            out += (start + end) / 2
            out += minOf(end, start + 2)
            start = end + 1
        }
        return out.toList()
    }

    private companion object {
        const val BUDGET_MILLIS = 5_000L
        const val MAX_CARETS = 120
    }
}
