package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.Type
import org.junit.Test

class TypeTextTest {
    @Test
    fun smartPairsAreOffByDefault() {
        assertCases(Type("("), listOf(Case("x‸", "x(‸"), Case("«sel»", "(‸")))
        assertCases(Type("`"), listOf(Case("‸", "`‸")))
    }

    @Test
    fun smartPairsWhenOn() {
        assertCases(
            Type("(", smartPairs = true),
            listOf(
                Case("‸", "(‸)"),
                Case("«x»", "(«x»)"),
                // Before a word an opener is just an opener.
                Case("‸word", "(‸word"),
            ),
        )
        assertCases(Type(")", smartPairs = true), listOf(Case("(x‸)", "(x)‸"), Case("x‸", "x)‸")))
        assertCases(
            Type("`", smartPairs = true),
            listOf(
                Case("run ‸", "run `‸`"),
                // After a word a backtick closes the span it is in.
                Case("`code‸", "`code`‸"),
                Case("`code‸`", "`code`‸"),
            ),
        )
        assertCases(Type("ab", smartPairs = true), listOf(Case("‸", "ab‸")))
    }
}
