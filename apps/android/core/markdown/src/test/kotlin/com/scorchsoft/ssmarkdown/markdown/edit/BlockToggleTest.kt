package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.ToggleBlockquote
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.ToggleCodeBlock
import org.junit.Test

class BlockToggleTest {
    @Test
    fun blockquote() =
        assertCases(
            ToggleBlockquote,
            listOf(
                Case("quo‸te", "> quo‸te"),
                Case("> quo‸te", "quo‸te"),
                Case(">quo‸te", "quo‸te"),
                Case("> > dee‸p", "> dee‸p"),
                Case("‸", "> ‸"),
                // Blank lines inside get a bare `>` so the quote stays one block, with no trailing space.
                Case("«one\n\ntwo»", "«> one\n>\n> two»"),
                Case("«> one\n>\n> two»", "«one\n\ntwo»"),
                Case("«> one\ntwo»", "«> > one\n> two»"),
            ),
        )

    @Test
    fun codeBlock() =
        assertCases(
            ToggleCodeBlock,
            listOf(
                Case("‸", "```\n‸\n```"),
                Case("val x‸ = 1", "```\nval x‸ = 1\n```"),
                Case("«a\nb»", "```\n«a\nb»\n```"),
                Case("```\nval x‸ = 1\n```", "val x‸ = 1"),
                Case("«```\na\n```»", "«a»"),
                Case("«```\n```»", "‸"),
                Case("‸", "~~~\n‸\n~~~", DocumentStyle(codeFence = "~~~")),
                // Content holding a fence gets a longer one, so it cannot close the block early.
                Case("«```\nnested\n```\nmore»", "````\n«```\nnested\n```\nmore»\n````"),
                Case("before\n«code»\nafter", "before\n```\n«code»\n```\nafter"),
            ),
        )
}
