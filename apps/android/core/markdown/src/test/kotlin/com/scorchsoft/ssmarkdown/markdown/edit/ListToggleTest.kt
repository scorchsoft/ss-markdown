package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.ToggleList
import org.junit.Test

class ListToggleTest {
    @Test
    fun bullets() =
        assertCases(
            ToggleList(ListKind.Bullet),
            listOf(
                Case("it‸em", "- it‸em"),
                Case("- it‸em", "it‸em"),
                Case("‸", "- ‸"),
                Case("it‸em", "* it‸em", DocumentStyle(bullet = '*')),
                Case("+ it‸em", "it‸em"),
                Case("1. it‸em", "- it‸em"),
                Case("- [ ] it‸em", "- it‸em"),
                Case("> quo‸ted", "> - quo‸ted"),
                Case("  nest‸ed", "  - nest‸ed"),
                Case("«one\n\ntwo»", "«- one\n\n- two»"),
                // Mixed: everything becomes a bullet before anything is removed.
                Case("«- one\ntwo»", "«- one\n- two»"),
                Case("«- one\n- two»", "«one\ntwo»"),
            ),
        )

    @Test
    fun numbered() =
        assertCases(
            ToggleList(ListKind.Numbered),
            listOf(
                Case("it‸em", "1. it‸em"),
                Case("1. it‸em", "it‸em"),
                Case("- it‸em", "1. it‸em"),
                Case("it‸em", "1) it‸em", DocumentStyle(orderedDelimiter = ')')),
                Case("«a\nb\nc»", "«1. a\n2. b\n3. c»"),
                // A list right above is continued, in its own delimiter.
                Case("1) a\n2) b\n‸c", "1) a\n2) b\n3) ‸c"),
                Case("«a\n  b\nc»", "«1. a\n  1. b\n2. c»"),
                Case("- [ ] ta‸sk", "1. [ ] ta‸sk"),
                // A parent and its child switched at once: the grandchild moves by both widths (MalformedMarkdownTest).
                Case("«- a\n  - b»\n    - c", "«1. a\n   1. b»\n      - c"),
            ),
        )

    @Test
    fun tasks() =
        assertCases(
            ToggleList(ListKind.Task),
            listOf(
                Case("it‸em", "- [ ] it‸em"),
                Case("- it‸em", "- [ ] it‸em"),
                Case("1. it‸em", "1. [ ] it‸em"),
                Case("- [ ] it‸em", "it‸em"),
                Case("- [x] it‸em", "it‸em"),
                Case("-‸", "- [ ] ‸"),
                Case("«a\nb»", "«- [ ] a\n- [ ] b»"),
            ),
        )
}
