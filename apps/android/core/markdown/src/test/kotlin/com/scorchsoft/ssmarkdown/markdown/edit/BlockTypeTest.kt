package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.SetBlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The ¶ menu (docs/ux-spec.md, "Formatting strip"): every block in the
 * selection becomes one type, its old block syntax replaced and its text
 * kept, and nothing outside the converted lines changes but the blank
 * lines and fences the new type needs.
 */
class BlockTypeTest {
    private val heavy = System.getProperty("ssmarkdown.heavyTests") == "true"
    private val seeds = if (heavy) 3_000 else 400

    @Test
    fun convertsBetweenEveryKind() {
        assertCases(
            SetBlockType(BlockType.Heading2),
            listOf(
                Case("- it‸em", "## it‸em"),
                Case("1. it‸em", "## it‸em"),
                Case("- [x] do‸ne", "## do‸ne"),
                Case("> quo‸te", "## quo‸te"),
                Case("# ti‸tle", "## ti‸tle"),
                Case("pl‸ain", "## pl‸ain"),
                Case("«- a\n- b»", "«## a\n## b»"),
            ),
        )
        assertCases(
            SetBlockType(BlockType.Task),
            listOf(
                Case("## he‸ading", "- [ ] he‸ading"),
                Case("- [x] do‸ne", "- [x] do‸ne"),
                Case("* it‸em", "* [ ] it‸em"),
                Case("  - nes‸ted", "  - [ ] nes‸ted"),
            ),
        )
        assertCases(
            SetBlockType(BlockType.Numbered),
            listOf(
                Case("«- a\n- b\n- c»", "«1. a\n2. b\n3. c»"),
                Case("1. a\n- b‸", "1. a\n2. b‸"),
            ),
        )
        assertCases(
            SetBlockType(BlockType.Bullet),
            listOf(Case("## he‸ading", "- he‸ading"), Case("«1. a\n2. b»", "«- a\n- b»")),
        )
        assertCases(
            SetBlockType(BlockType.Quote),
            listOf(Case("- it‸em", "> it‸em"), Case("«a\n\nb»", "«> a\n>\n> b»")),
        )
    }

    @Test
    fun aParagraphNeverJoinsTheBlockAboveOrBelow() =
        assertCases(
            SetBlockType(BlockType.Paragraph),
            listOf(
                Case("# ti‸tle", "ti‸tle"),
                // Right under a list item a bare line would be part of it.
                Case("- a\n- b‸\n- c", "- a\n\nb‸\n- c"),
                // Each item is its own paragraph.
                Case("«- a\n- b»", "«a\n\nb»"),
                // Lines of one paragraph stay one paragraph.
                Case("«one\ntwo»", "«one\ntwo»"),
                // A heading above a plain line: the line below stays its own paragraph.
                Case("## he‸ad\nnext", "he‸ad\n\nnext"),
                Case("> quo‸te", "quo‸te"),
            ),
        )

    @Test
    fun codeBlocksWrapAndUnwrap() {
        assertCases(
            SetBlockType(BlockType.CodeBlock),
            listOf(
                Case("«- a\n- b»", "```\n«a\nb»\n```"),
                Case("x ``` y‸", "````\nx ``` y‸\n````"),
            ),
        )
        assertCases(
            SetBlockType(BlockType.Heading1),
            listOf(
                Case("```\nco‸de\n```", "# co‸de"),
                Case("para\n\n```\na‸\n- b\n```\nnext", "para\n\n# a‸\n# - b\nnext"),
            ),
        )
        assertCases(
            SetBlockType(BlockType.Paragraph),
            listOf(Case("```\n  co‸de\n```\nnext", "co‸de\n\nnext")),
        )
        assertCases(SetBlockType(BlockType.CodeBlock), listOf(Case("```\nco‸de\n```", "```\nco‸de\n```")))
    }

    @Test
    fun readsTheCurrentType() {
        fun at(source: String): BlockType? {
            val m = marked(source)
            return BlockType.at(m.text, m.selection, FenceStates.scan(m.text))
        }
        assertEquals(BlockType.Paragraph, at("pl‸ain"))
        assertEquals(BlockType.Heading1, at("# h‸"))
        assertEquals(BlockType.Heading3, at("### h‸"))
        assertEquals(null, at("#### h‸"))
        assertEquals(BlockType.Bullet, at("- a‸"))
        assertEquals(BlockType.Numbered, at("3. a‸"))
        assertEquals(BlockType.Task, at("- [ ] a‸"))
        assertEquals(BlockType.Quote, at("> a‸"))
        assertEquals(BlockType.CodeBlock, at("```\nco‸de\n```"))
    }

    /** The text of each line once its block syntax is off: what every conversion must keep. */
    private fun texts(
        text: String,
        fences: FenceStates = FenceStates.scan(text),
    ): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start <= text.length) {
            val end = TextLines.lineEnd(text, start)
            val line = text.substring(start, end)
            val role = fences.roleAt(start)
            when {
                role == FenceRole.OPENING_FENCE || role == FenceRole.CLOSING_FENCE -> Unit
                line.isBlank() || line.trim() == ">" -> Unit
                role == FenceRole.INSIDE -> out += line.trim()
                else -> out += bare(line)
            }
            start = end + 1
        }
        return out
    }

    private fun bare(line: String): String {
        val p = LinePrefix.parse(line)
        if (p.isListItem) return p.content.trim()
        val rest = line.substring(p.quoteEnd).trimStart()
        val (level, end) = HeadingEdit.parse(rest)
        return (if (level > 0) rest.substring(end) else rest).trim()
    }

    @Test
    fun everyConversionKeepsTheTextAndTouchesOnlyItsLines() {
        val blocks =
            listOf(
                "plain words",
                "# Title",
                "## Sub",
                "- item",
                "* star",
                "1. one",
                "2) two",
                "- [ ] todo",
                "- [x] done",
                "> quoted",
                "> - quoted item",
                "  - nested",
                "```\ncode\n  more code\n```",
                "",
            )
        repeat(seeds) { seed ->
            val random = Random(seed)
            val text = List(random.nextInt(1, 8)) { blocks.random(random) }.joinToString("\n")
            val a = random.nextInt(text.length + 1)
            val b = if (random.nextBoolean()) a else random.nextInt(text.length + 1)
            val selection = TextSelection(minOf(a, b), maxOf(a, b))
            for (type in BlockType.entries) {
                val result =
                    runCatching { SetBlockType(type).apply(text, selection) }
                        .getOrElse {
                            throw AssertionError(
                                "$type seed $seed on ${text.replace("\n", "⏎")} $selection",
                                it,
                            )
                        }
                val after = result.applyTo(text)
                val message = "$type seed $seed on ${text.replace("\n", "⏎")} → ${after.replace("\n", "⏎")}"
                assertEquals(message, texts(text), texts(after))
                assertTrue(message, result.selection.end <= after.length)
                if (result.isNoOp) continue
                // Only the converted lines change: before the first touched line and after the last, all is identical.
                val first = TextLines.lineStart(text, result.ops.minOf { it.offset })
                val lastEnd = TextLines.lineEnd(text, result.ops.maxOf { it.offset + it.deleted.length })
                assertEquals(message, text.substring(0, first), after.substring(0, first))
                assertTrue(message, after.endsWith(text.substring(lastEnd)))
                // The type it asked for is what the first converted line now is.
                val converted =
                    BlockType.at(
                        after,
                        TextSelection.caret(minOf(result.selection.start, after.length)),
                        FenceStates.scan(after),
                    )
                if (texts(text).isNotEmpty() &&
                    TextLines.lineAt(after, minOf(result.selection.start, after.length)).content.isNotBlank()
                ) {
                    assertEquals(message, type, converted)
                }
            }
        }
    }
}
