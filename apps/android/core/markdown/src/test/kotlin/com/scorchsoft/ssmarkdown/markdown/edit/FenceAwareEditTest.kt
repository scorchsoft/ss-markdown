package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.CycleHeading
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.Newline
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.RenumberList
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.SetHeading
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.ToggleCodeBlock
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.ToggleList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Line commands read the tokenizer's fence state, never the look of nearby lines. */
class FenceAwareEditTest {
    @Test
    fun `scan reports every line's role, nested fences and unclosed blocks included`() {
        val text = "para\n```kotlin\n- x\n~~~\n```\nafter\n  ~~~~\n  in\n"
        val fences = FenceStates.scan(text)
        val roles = lineStarts(text).map { fences.roleAt(it) }
        assertEquals(
            listOf(
                FenceRole.OUTSIDE,
                FenceRole.OPENING_FENCE,
                FenceRole.INSIDE,
                FenceRole.INSIDE,
                FenceRole.CLOSING_FENCE,
                FenceRole.OUTSIDE,
                FenceRole.OPENING_FENCE,
                FenceRole.INSIDE,
            ),
            roles,
        )
    }

    @Test
    fun `heading and list toggles never touch fenced code`() {
        val inCode = "```\nco‸de\n```"
        assertEquals(inCode, run(SetHeading(2), inCode))
        assertEquals(inCode, run(CycleHeading, inCode))
        for (kind in ListKind.entries) assertEquals(inCode, run(ToggleList(kind), inCode))
        // A selection over prose and code changes only the prose.
        assertEquals("«## a\n```\nb\n```\n## c»", run(SetHeading(2), "«a\n```\nb\n```\nc»"))
        assertEquals("«- a\n```\nb\n```\n- c»", run(ToggleList(ListKind.Bullet), "«a\n```\nb\n```\nc»"))
    }

    @Test
    fun `enter inside a fence keeps indentation but never continues a list`() {
        assertEquals("```\n- not a list\n‸\n```", run(Newline, "```\n- not a list‸\n```"))
        assertEquals("```\n  1. code\n  ‸\n```", run(Newline, "```\n  1. code‸\n```"))
        assertEquals("> ```\n> - x\n> ‸", run(Newline, "> ```\n> - x‸"))
    }

    @Test
    fun `code block toggle unwraps the block the caret is in, however long`() {
        assertEquals("a\nb‸\nc", run(ToggleCodeBlock, "```\na\nb‸\nc\n```"))
        assertEquals("x\n«a\nb»\ny", run(ToggleCodeBlock, "x\n```\n«a\nb»\n```\ny"))
        // An unclosed block runs to the end: only its opener goes.
        assertEquals("a\nb‸", run(ToggleCodeBlock, "```\na\nb‸"))
    }

    @Test
    fun `text between two blocks is wrapped, not mistaken for a block`() {
        val before = "```\none\n```\nmid‸dle\n```\ntwo\n```"
        val after = run(ToggleCodeBlock, before)
        assertEquals("```\none\n```\n```\nmid‸dle\n```\n```\ntwo\n```", after)
    }

    @Test
    fun `a supplied lookup is what the command trusts`() {
        // The editor's tokenizer says the caret line is plain text, so the heading applies.
        val (text, selection) = marked("```\nco‸de\n```")
        val result = SetHeading(1).apply(text, selection, DocumentStyle.DEFAULT, FenceStates.NONE)
        assertEquals("```\n# code\n```", result.applyTo(text))
    }

    @Test
    fun `renumber list closes the gap a deleted item leaves`() {
        val cases =
            listOf(
                "1. a\n‸3. c\n4. d" to "1. a\n‸2. c\n3. d",
                "1. a\n   1. x\n   ‸3. y\n2. b" to "1. a\n   1. x\n   ‸2. y\n2. b",
                "1. a\n1. b\n‸1. c" to "1. a\n1. b\n‸1. c",
                "3. a\n‸5. b" to "3. a\n‸4. b",
                "- a\n‸- b" to "- a\n‸- b",
            )
        for ((before, after) in cases) {
            val (text, selection) = marked(before)
            val result = RenumberList(selection.start).apply(text, selection)
            assertEquals(before, after, render(result.applyTo(text), result.selection))
        }
    }

    @Test
    fun `a windowed scan on a huge file stays bounded and correct near the query`() {
        val filler = "plain line of prose\n".repeat(FenceStates.FULL_SCAN_CHARS / 20 + 10)
        val text = filler + "```\ncode\n```\n"
        val fences = FenceStates.scan(text)
        val code = text.indexOf("code\n", filler.length)
        assertEquals(FenceRole.INSIDE, fences.roleAt(code))
        assertTrue(fences.roleAt(0) == FenceRole.OUTSIDE)
    }

    private fun lineStarts(text: String): List<Int> {
        val starts = mutableListOf(0)
        text.forEachIndexed { i, c -> if (c == '\n' && i + 1 < text.length) starts += i + 1 }
        return starts
    }
}
