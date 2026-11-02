package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.Newline
import org.junit.Test

class ListContinuationTest {
    @Test
    fun continuesItems() =
        assertCases(
            Newline,
            listOf(
                Case("- one‸", "- one\n- ‸"),
                Case("* one‸", "* one\n* ‸"),
                Case("+   one‸", "+   one\n+   ‸"),
                Case("- [x] done‸", "- [x] done\n- [ ] ‸"),
                Case("- [ ] todo‸", "- [ ] todo\n- [ ] ‸"),
                Case("1. one‸", "1. one\n2. ‸"),
                Case("9) nine‸", "9) nine\n10) ‸"),
                Case("  - nested‸", "  - nested\n  - ‸"),
                Case("> - quoted‸", "> - quoted\n> - ‸"),
                // Splitting an item moves the rest onto the new one.
                Case("- one‸two", "- one\n- ‸two"),
                Case("- «sel»ected", "- \n- ‸ected"),
                Case("- 🎉‸ party", "- 🎉\n- ‸ party"),
            ),
        )

    @Test
    fun renumbersTheRestOfTheList() =
        assertCases(
            Newline,
            listOf(
                Case("1. a‸\n2. b\n3. c", "1. a\n2. ‸\n3. b\n4. c"),
                Case("1. a‸\n   nested text\n2. b", "1. a\n2. ‸\n   nested text\n3. b"),
                Case("1. a‸\n\n2. b", "1. a\n2. ‸\n\n3. b"),
                // Stops at the first number that is already right, and at the end of the list.
                Case("1. a‸\n3. b\n4. c", "1. a\n2. ‸\n3. b\n4. c"),
                Case("1. a‸\n2. b\n\npara\n1. other", "1. a\n2. ‸\n3. b\n\npara\n1. other"),
                Case("1. a‸\n2) b", "1. a\n2. ‸\n2) b"),
                // A list numbered 1. 1. 1. on purpose keeps doing that.
                Case("1. a\n1. b‸\n1. c", "1. a\n1. b\n1. ‸\n1. c"),
            ),
        )

    @Test
    fun emptyItemEndsTheList() =
        assertCases(
            Newline,
            listOf(
                // A blank line first: right under the item, a bare line would be a lazy
                // continuation of it, still inside the list (CommonMark).
                Case("- one\n- ‸", "- one\n\n‸"),
                Case("- one\n- [ ] ‸", "- one\n\n‸"),
                Case("1. one\n2. ‸", "1. one\n\n‸"),
                Case("- one\n-‸", "- one\n\n‸"),
                Case("- ‸", "‸"),
                Case("para\n\n- ‸", "para\n\n‸"),
                // In the middle of a list the rest stays a list after the new paragraph.
                Case("- a\n- ‸\n- b", "- a\n\n‸\n- b"),
                // ...and a numbered rest counts from 1 again.
                Case("1. a\n2. ‸\n3. b\n4. c", "1. a\n\n‸\n1. b\n2. c"),
                // A nested empty item steps out one level instead.
                Case("- one\n  - ‸", "- one\n- ‸"),
                Case("> - one\n> - ‸", "> - one\n>\n> ‸"),
            ),
        )

    @Test
    fun outdentOnATopLevelItemLeavesTheList() =
        assertCases(
            EditCommand.Outdent,
            listOf(
                Case("- one\n- tw‸o", "- one\n\ntw‸o"),
                Case("- on‸e", "on‸e"),
                Case("1. a\n2. «b»", "1. a\n\n«b»"),
                Case("- [ ] ta‸sk", "ta‸sk"),
                Case("> - a\n> - b‸", "> - a\n>\n> b‸"),
                // Nested items still step out one level.
                Case("- one\n  - tw‸o", "- one\n- tw‸o"),
                // Plain lines are unchanged.
                Case("pla‸in", "pla‸in"),
            ),
        )

    @Test
    fun quotesAndIndentation() =
        assertCases(
            Newline,
            listOf(
                Case("> quote‸", "> quote\n> ‸"),
                Case("> ‸", "‸"),
                Case("    code‸", "    code\n    ‸"),
                Case("plain‸", "plain\n‸"),
                Case("‸- item", "\n‸- item"),
                Case("«a\nb»", "\n‸"),
            ),
        )
}
