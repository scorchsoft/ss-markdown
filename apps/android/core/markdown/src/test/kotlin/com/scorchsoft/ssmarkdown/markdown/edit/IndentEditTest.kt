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
                Case("1. a\n2. b‸", "1. a\n   2. b‸"),
                Case("10. a\n11. b‸", "10. a\n    11. b‸"),
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
}
