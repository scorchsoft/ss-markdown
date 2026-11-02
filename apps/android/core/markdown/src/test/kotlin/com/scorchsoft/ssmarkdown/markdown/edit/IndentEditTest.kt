package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.Indent
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.Outdent
import org.junit.Test

class IndentEditTest {
    private val tabs = DocumentStyle(indentUnit = "\t")

    @Test
    fun indent() =
        assertCases(
            Indent,
            listOf(
                // List items nest by the marker width of the item above.
                Case("- a\n- b‸", "- a\n  - b‸"),
                // A numbered item that starts a sublist starts it at 1.
                Case("1. a\n2. b‸", "1. a\n   1. b‸"),
                Case("10. a\n11. b‸", "10. a\n    1. b‸"),
                Case("- a\n- [ ] b‸", "- a\n  - [ ] b‸"),
                Case("> - a\n> - b‸", "> - a\n>   - b‸"),
                Case("- a\n- b‸", "- a\n\t- b‸", tabs),
                Case("pl‸ain", "    pl‸ain"),
                Case("pl‸ain", "\tpl‸ain", tabs),
                Case("‸x", "    ‸x"),
                Case("«- a\n- b»", "«  - a\n  - b»"),
            ),
        )

    @Test
    fun outdent() =
        assertCases(
            Outdent,
            listOf(
                Case("- a\n  - b‸", "- a\n- b‸"),
                Case("1. a\n   - b‸", "1. a\n- b‸"),
                Case("- a\n  - b\n    - c‸", "- a\n  - b\n  - c‸"),
                Case("\t- b‸", "- b‸"),
                Case("      pl‸ain", "  pl‸ain"),
                Case("  pl‸ain", "pl‸ain"),
                Case("pl‸ain", "pl‸ain"),
                Case("> - a\n>   - b‸", "> - a\n> - b‸"),
            ),
        )

    @Test
    fun `indent and outdent renumber the numbered lists they reshape`() =
        assertCases(
            Indent,
            listOf(
                // The items after the moved one close the gap it leaves.
                Case("1. a\n2. b‸\n3. c\n4. d", "1. a\n   1. b‸\n2. c\n3. d"),
                // Joining an existing sublist continues its count.
                Case("1. a\n   1. x\n2. b‸\n3. c", "1. a\n   1. x\n   2. b‸\n2. c"),
                // A whole selection moves together and counts from 1.
                Case("1. a\n«2. b\n3. c»\n4. d", "1. a\n«   1. b\n   2. c»\n2. d"),
                // A list numbered 1. 1. 1. on purpose stays that way.
                Case("1. a\n1. b\n1. c‸\n1. d", "1. a\n1. b\n   1. c‸\n1. d"),
                // A list that starts at 5 keeps its start.
                Case("5. a\n6. b‸\n7. c", "5. a\n   1. b‸\n6. c"),
                // Bullets are left alone; `1)` lists count separately from `1.`.
                Case("- a\n- b‸\n- c", "- a\n  - b‸\n- c"),
            ),
        )

    @Test
    fun `outdent renumbers both levels`() =
        assertCases(
            Outdent,
            listOf(
                Case("1. a\n   1. x‸\n   2. y\n2. b", "1. a\n2. x‸\n   1. y\n3. b"),
                Case("1. a\n   1. x\n   2. y‸\n2. b", "1. a\n   1. x\n2. y‸\n3. b"),
            ),
        )

    @Test
    fun `fenced code is never renumbered and a fence at the margin ends the list`() =
        assertCases(
            Indent,
            listOf(
                Case(
                    "1. a\n2. b‸\n\n```\n3. not a list\n```\n3. c",
                    "1. a\n   1. b‸\n\n```\n3. not a list\n```\n3. c",
                ),
                // A fence inside an item is part of the list; its lines are still code.
                Case(
                    "1. a\n2. b‸\n   ```\n   3. code\n   ```\n3. c",
                    "1. a\n   1. b‸\n   ```\n   3. code\n   ```\n2. c",
                ),
            ),
        )
}
