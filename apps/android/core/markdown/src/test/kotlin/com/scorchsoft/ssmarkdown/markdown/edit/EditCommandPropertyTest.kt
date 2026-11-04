package com.scorchsoft.ssmarkdown.markdown.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Every command, over random Markdown-ish documents and selections:
 *
 * - the result's invariants hold (sorted, non-overlapping, minimal ops);
 * - **no byte outside the edited lines changes**: everything before the
 *   first touched line and after the last one is identical;
 * - the toggles undo themselves: applying one twice gives the text back.
 */
class EditCommandPropertyTest {
    private val heavy = System.getProperty("ssmarkdown.heavyTests") == "true"
    private val seeds = if (heavy) 5_000 else 400

    private val fragments =
        listOf(
            "- item",
            "* star",
            "+ plus",
            "1. one",
            "2) two",
            "- [ ] task",
            "- [x] done",
            "> quote",
            "> - quoted item",
            "  - nested",
            "    deep",
            "\t- tabbed",
            "## Heading",
            "plain text",
            "**bold** and _it_",
            "`code` span",
            "```",
            "~~~",
            "| a | b |",
            "|-|-|",
            "",
            "",
            "👋🏽 emoji ✨",
            "日本語のテキスト",
            "snake_case_name",
            "a ~~b~~ c",
            // Empty items, which Backspace deletes with their line, and unclosed blocks.
            "- ",
            "1. ",
            "  - ",
            "> - ",
            "- [ ] ",
            "<!--",
            "$$",
        )

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
                EditCommand.Type("(", smartPairs = true),
            )

    @Test
    fun editsStayInsideTheTouchedLines() {
        repeat(seeds) { seed ->
            val random = Random(seed)
            val text = document(random)
            val selection = selection(random, text)
            val style = if (random.nextBoolean()) DocumentStyle.DEFAULT else DocumentStyle.detect(text)
            for (command in commands) {
                val result = command.apply(text, selection, style)
                val after = result.applyTo(text)
                assertInvariants(text, result, after)
                assertUntouchedOutside(text, after, result, command, seed)
            }
        }
    }

    @Test
    fun togglesAreTheirOwnInverse() {
        val toggles =
            listOf(EditCommand.ToggleBlockquote, EditCommand.SetHeading(0)) +
                ListKind.entries.map { EditCommand.ToggleList(it) }
        repeat(seeds) { seed ->
            val random = Random(seed)
            // Plain lines, so "already that kind" is decided by the first press alone.
            val text = List(random.nextInt(1, 5)) { "line $it ${random.nextInt(100)}" }.joinToString("\n")
            val selection = selection(random, text)
            for (command in toggles) {
                val once = command.apply(text, selection)
                val twice = command.apply(once.applyTo(text), once.selection)
                val back = twice.applyTo(once.applyTo(text))
                if (command is EditCommand.ToggleList) {
                    // A list made between paragraph lines is kept apart from them by blank lines (EB-LIST-37),
                    // and those stay: every line's text comes back, in order.
                    assertEquals("$command seed $seed", text.lines(), back.lines().filter { it.isNotEmpty() })
                } else {
                    assertEquals("$command seed $seed", text, back)
                }
            }
        }
    }

    @Test
    fun inlineTogglesUndoThemselvesOnWords() {
        repeat(seeds) { seed ->
            val random = Random(seed)
            val words = List(random.nextInt(1, 6)) { "w${random.nextInt(1000)}" }
            val text = words.joinToString(" ")
            val start = text.indexOf(words.random(random))
            val selection = TextSelection(start, start + text.substring(start).takeWhile { it != ' ' }.length)
            for (format in InlineFormat.entries) {
                val command = EditCommand.ToggleInline(format)
                val once = command.apply(text, selection)
                val twice = command.apply(once.applyTo(text), once.selection)
                assertEquals("$format seed $seed", text, twice.applyTo(once.applyTo(text)))
                assertEquals("$format seed $seed", selection, twice.selection)
            }
        }
    }

    private fun assertUntouchedOutside(
        text: String,
        after: String,
        result: EditResult,
        command: EditCommand,
        seed: Int,
    ) {
        if (result.isNoOp) return
        val firstOffset = result.ops.minOf { it.offset }
        val lastEnd = result.ops.maxOf { it.offset + it.deleted.length }
        val head = TextLines.lineStart(text, firstOffset)
        val tailStart = TextLines.lineEnd(text, lastEnd)
        val tailLength = text.length - tailStart
        val message = "$command seed $seed on ${text.replace("\n", "⏎")}"
        assertEquals(message, text.substring(0, head), after.substring(0, head))
        assertTrue(message, after.endsWith(text.substring(tailStart)))
        assertTrue(message, after.length - tailLength >= head)
    }

    private fun document(random: Random): String =
        List(random.nextInt(1, 9)) {
            fragments.random(random)
        }.joinToString("\n")

    private fun selection(
        random: Random,
        text: String,
    ): TextSelection {
        val a = random.nextInt(text.length + 1)
        val b = if (random.nextBoolean()) a else random.nextInt(text.length + 1)
        return TextSelection(minOf(a, b), maxOf(a, b))
    }
}
