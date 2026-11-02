package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.InsertLink
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.InsertTable
import org.junit.Test

class InsertEditTest {
    private val selectedTable =
        """
        | «Column 1» | Column 2 | Column 3 |
        | -------- | -------- | -------- |
        |          |          |          |
        |          |          |          |
        """.trimIndent()

    @Test
    fun link() =
        assertCases(
            InsertLink,
            listOf(
                Case("see «the docs» now", "see [the docs](‸) now"),
                Case("see ‸ now", "see [‸]() now"),
                Case("«https://scorchsoft.com»", "[‸](https://scorchsoft.com)"),
                Case("«www.example.org»", "[‸](www.example.org)"),
                Case("«👋 hi»", "[👋 hi](‸)"),
            ),
        )

    @Test
    fun paddedTable() =
        assertCases(
            InsertTable(),
            listOf(
                Case("‸", selectedTable),
                Case("‸\nNext", "$selectedTable\n\nNext"),
                Case("Intro‸\nNext", "Intro\n\n$selectedTable\n\nNext"),
            ),
        )

    @Test
    fun compactTableFollowsTheDocument() =
        assertCases(
            InsertTable(columns = 2, rows = 1),
            listOf(
                Case(
                    "para\n‸",
                    "para\n\n|«Column 1»|Column 2|\n|---|---|\n| | |",
                    DocumentStyle(paddedTables = false),
                ),
            ),
        )
}
