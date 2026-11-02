package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.ToggleInline
import org.junit.Test

class InlineToggleTest {
    private val underscores = DocumentStyle(emphasis = '_', strong = '_')

    @Test
    fun bold() =
        assertCases(
            ToggleInline(InlineFormat.Bold),
            listOf(
                Case("a «word» here", "a **«word»** here"),
                Case("a **«word»** here", "a «word» here"),
                Case("a «**word**» here", "a «word» here"),
                Case("a **wo‸rd** here", "a wo‸rd here"),
                Case("a «word» here", "a __«word»__ here", underscores),
                // An existing span comes off whatever its char, so a mixed file still toggles.
                Case("a __«word»__ here", "a «word» here"),
                Case("x ‸ y", "x **‸** y"),
                Case("x **‸** y", "x ‸ y"),
                // Edge spaces stay outside the markers, or the span would not render.
                Case("a« word »here", "a **«word»** here"),
                Case("«   »", "«   »"),
                Case("***«both»***", "*«both»*"),
                Case("**b‸old** and **more**", "b‸old and **more**"),
                Case("**bold** and ‸x", "**bold** and **‸**x"),
            ),
        )

    @Test
    fun italic() =
        assertCases(
            ToggleInline(InlineFormat.Italic),
            listOf(
                Case("«word»", "*«word»*"),
                Case("*«word»*", "«word»"),
                Case("«word»", "_«word»_", underscores),
                Case("_wo‸rd_", "wo‸rd"),
                // Bold is not italic: its `**` is not taken for an italic pair.
                Case("**«word»**", "***«word»***"),
                Case("***«both»***", "**«both»**"),
                // Underscores inside a word are not emphasis.
                Case("snake_ca‸se_name", "snake_ca*‸*se_name"),
                Case("- item ‸", "- item *‸*"),
                Case("x *‸* y", "x ‸ y"),
            ),
        )

    @Test
    fun strikethroughAndCode() {
        assertCases(
            ToggleInline(InlineFormat.Strikethrough),
            listOf(
                Case("«gone»", "~~«gone»~~"),
                Case("~~go‸ne~~", "go‸ne"),
            ),
        )
        assertCases(
            ToggleInline(InlineFormat.Code),
            listOf(
                Case("run «gradle»", "run `«gradle»`"),
                Case("run `gra‸dle`", "run gra‸dle"),
                // A backtick inside needs a longer, padded fence.
                Case("«a`b»", "`` «a`b» ``"),
                // Bold markers inside a code span are code, not bold.
                Case("`**x‸**`", "**x‸**"),
            ),
        )
    }

    @Test
    fun boldInsideCodeIsNotBold() =
        assertCases(
            ToggleInline(InlineFormat.Bold),
            listOf(Case("`**x‸**`", "`**x**‸****`")),
        )

    @Test
    fun multiLineSelectionWrapsEachLine() =
        assertCases(
            ToggleInline(InlineFormat.Bold),
            listOf(
                Case("«one\n\ntwo»", "**«one**\n\n**two»**"),
                Case("**«one**\n**two»**", "«one\ntwo»"),
                // Partly bold: the plain lines are wrapped first, so the next press clears all.
                Case("«**one**\ntwo»", "«**one**\n**two»**"),
            ),
        )

    @Test
    fun emojiAndWideCharactersAreWrappedWhole() =
        assertCases(
            ToggleInline(InlineFormat.Bold),
            listOf(
                Case("say «👋🏽 hi»", "say **«👋🏽 hi»**"),
                Case("**«日本語»**", "«日本語»"),
            ),
        )
}
