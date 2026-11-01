package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.CycleHeading
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.SetHeading
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingEditTest {
    @Test
    fun setLevel() {
        assertCases(
            SetHeading(2),
            listOf(
                Case("Ti‸tle", "## Ti‸tle"),
                Case("# Ti‸tle", "## Ti‸tle"),
                Case("###### Ti‸tle", "## Ti‸tle"),
                Case("‸", "## ‸"),
                Case("> Quo‸ted", "> ## Quo‸ted"),
                Case("«one\ntwo»", "«## one\n## two»"),
                // Not headings: no space after the hashes, or seven of them.
                Case("#hash‸tag", "## #hash‸tag"),
                Case("####### x‸", "## ####### x‸"),
            ),
        )
        assertCases(SetHeading(0), listOf(Case("### Ti‸tle", "Ti‸tle"), Case("Plain‸", "Plain‸")))
    }

    @Test
    fun cycleGoesOneTwoThreeThenOff() =
        assertCases(
            CycleHeading,
            listOf(
                Case("x‸", "# x‸"),
                Case("# x‸", "## x‸"),
                Case("## x‸", "### x‸"),
                Case("### x‸", "x‸"),
                Case("##### x‸", "x‸"),
            ),
        )

    @Test
    fun levelChangeIsOneCharEdit() {
        val (text, selection) = marked("## Ti‸tle")
        val result = SetHeading(3).apply(text, selection)
        assertEquals(1, result.ops.size)
        assertEquals(
            1,
            result.ops
                .single()
                .inserted.length +
                result.ops
                    .single()
                    .deleted.length,
        )
    }
}
