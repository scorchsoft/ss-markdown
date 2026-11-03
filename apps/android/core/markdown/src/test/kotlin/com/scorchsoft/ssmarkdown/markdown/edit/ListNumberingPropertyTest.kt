package com.scorchsoft.ssmarkdown.markdown.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Over random nested lists, some holding fenced code: a list counted
 * 1, 2, 3… at every level stays counted that way after any indent,
 * outdent, item insert (Enter) or item delete (+ [EditCommand.RenumberList]),
 * and no line of code ever changes.
 */
class ListNumberingPropertyTest {
    private val heavy = System.getProperty("ssmarkdown.heavyTests") == "true"
    private val seeds = if (heavy) 5_000 else 500

    @Test
    fun `indent and outdent keep every level counting from one`() {
        repeat(seeds) { seed ->
            val random = Random(seed)
            val text = list(random)
            val caret = itemStarts(text).random(random) + 1
            val command = if (random.nextBoolean()) EditCommand.Indent else EditCommand.Outdent
            val result = command.apply(text, TextSelection.caret(caret))
            val after = result.applyTo(text)
            assertInvariants(text, result, after)
            assertCanonical("$command seed $seed\n$text\n→\n$after", after)
            assertEquals("code changed, seed $seed", codeLines(text), codeLines(after))
        }
    }

    @Test
    fun `enter on an item keeps the count`() {
        repeat(seeds) { seed ->
            val random = Random(seed)
            val text = list(random)
            val start = itemStarts(text).random(random)
            val caret = TextLines.lineEnd(text, start)
            val after = EditCommand.Newline.apply(text, TextSelection.caret(caret)).applyTo(text)
            assertCanonical("enter seed $seed\n$text\n→\n$after", after)
        }
    }

    @Test
    fun `deleting an item then renumbering keeps the count`() {
        repeat(seeds) { seed ->
            val random = Random(seed)
            val text = list(random)
            val start = itemStarts(text).random(random)
            val end = TextLines.lineEnd(text, start)
            // Delete the item's whole line, as backspacing over it does.
            val deleted =
                if (end <
                    text.length
                ) {
                    text.removeRange(start, end + 1)
                } else {
                    text.removeRange(maxOf(0, start - 1), end)
                }
            val at = minOf(start, deleted.length)
            val result = EditCommand.RenumberList(at).apply(deleted, TextSelection.caret(at))
            val after = result.applyTo(deleted)
            assertInvariants(deleted, result, after)
            assertCanonical("delete seed $seed\n$text\n→\n$after", after, fromOffset = TextLines.lineStart(after, at))
            assertEquals(codeLines(deleted), codeLines(after))
        }
    }

    @Test
    fun `renumbering an already counted list changes nothing`() {
        repeat(seeds) { seed ->
            val random = Random(seed)
            val text = list(random)
            for (start in itemStarts(text)) {
                assertTrue(EditCommand.RenumberList(start).apply(text, TextSelection.caret(0)).isNoOp)
            }
        }
    }

    /** A canonical list: levels nest at their parent's content column; each numbered level counts from 1. */
    private fun list(random: Random): String {
        val lines = ArrayList<String>()
        val columns = ArrayList<Int>()
        val counters = ArrayList<Int>()
        val ordered = ArrayList<Boolean>()
        repeat(random.nextInt(2, 14)) {
            val depth = random.nextInt(0, minOf(columns.size, 2) + 1)
            while (columns.size > depth + 1) {
                columns.removeAt(columns.lastIndex)
                counters.removeAt(counters.lastIndex)
                ordered.removeAt(ordered.lastIndex)
            }
            val indent = if (depth == 0) 0 else columns[depth - 1]
            if (counters.size == depth) {
                counters += 0
                ordered += random.nextInt(4) != 0
                columns += 0
            }
            counters[depth]++
            val marker = if (ordered[depth]) "${counters[depth]}. " else "- "
            columns[depth] = indent + marker.length
            lines += " ".repeat(indent) + marker + "item"
            if (random.nextInt(6) == 0) {
                val pad = " ".repeat(columns[depth])
                lines += listOf("$pad```", "${pad}1. code", "$pad```")
            }
        }
        return lines.joinToString("\n")
    }

    private fun itemStarts(text: String): List<Int> {
        val fences = FenceStates.scan(text)
        val starts = ArrayList<Int>()
        var start = 0
        while (start <= text.length) {
            val end = TextLines.lineEnd(text, start)
            if (!fences.roleAt(start).isCode && LinePrefix.parse(text.substring(start, end)).isListItem) starts += start
            start = end + 1
        }
        return starts
    }

    private fun codeLines(text: String): List<String> {
        val fences = FenceStates.scan(text)
        val code = ArrayList<String>()
        var start = 0
        while (start <= text.length) {
            val end = TextLines.lineEnd(text, start)
            if (fences.roleAt(start) == FenceRole.INSIDE) code += text.substring(start, end).trim()
            start = end + 1
        }
        return code
    }

    private fun isMarginParagraph(
        fences: FenceStates,
        start: Int,
        end: Int,
        p: LinePrefix,
    ): Boolean = end > start && !fences.roleAt(start).isCode && p.indentWidth + p.quoteEnd == 0

    /**
     * An independent check of the result: every numbered level counts up by
     * one, a level that starts at or below [fromOffset] starts at 1, and a
     * top-level list split by a paragraph counts on across it.
     */
    private fun assertCanonical(
        message: String,
        text: String,
        fromOffset: Int = 0,
    ) {
        val fences = FenceStates.scan(text)
        val stack = ArrayList<Pair<Int, Long?>>() // width to the next expected number (null: bullets)
        var start = 0
        while (start <= text.length) {
            val end = TextLines.lineEnd(text, start)
            val p = LinePrefix.parse(text.substring(start, end))
            if (!fences.roleAt(start).isCode && p.isListItem) {
                val width = p.indentWidth
                while (stack.isNotEmpty() && stack.last().first > width) stack.removeAt(stack.lastIndex)
                val number = (p.marker as? ListMarker.Ordered)?.number
                val top = stack.lastOrNull()?.takeIf { it.first == width }
                if (top != null) stack.removeAt(stack.lastIndex)
                val expected =
                    when {
                        number == null -> null
                        top?.second != null -> top.second
                        start >= fromOffset -> 1L
                        else -> number
                    }
                assertEquals(message, expected, number)
                stack += width to number?.plus(1)
            } else if (isMarginParagraph(fences, start, end, p)) {
                // A paragraph at the margin (an item outdented out of the list) splits the list: the
                // nested levels end, and the top-level list after it continues the count (ux-spec).
                stack.retainAll { it.first == 0 }
            }
            start = end + 1
        }
    }
}
