package com.scorchsoft.ssmarkdown.markdown.edit

import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand.ToggleTaskCheckbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R12: a checkbox tap changes exactly one char and nothing else. */
class TaskCheckboxTest {
    private val rows =
        listOf(
            "- [ ] buy milk" to "- [x] buy milk",
            "- [x] buy milk" to "- [ ] buy milk",
            "- [X] buy milk" to "- [ ] buy milk",
            "1. [ ] first" to "1. [x] first",
            "> * [ ] quoted" to "> * [x] quoted",
            "    - [ ] nested" to "    - [x] nested",
            "- [ ]" to "- [x]",
            "- [ ] 🎉 party" to "- [x] 🎉 party",
        )

    @Test
    fun togglesExactlyOneChar() {
        for ((before, after) in rows) {
            val doc = "intro\n$before\noutro"
            val result = ToggleTaskCheckbox(doc.indexOf(before) + 2).apply(doc, TextSelection.caret(0))
            assertEquals(before, "intro\n$after\noutro", result.applyTo(doc))
            val op = result.ops.single()
            assertEquals(1, op.deleted.length)
            assertEquals(1, op.inserted.length)
            assertEquals("selection is untouched", TextSelection.caret(0), result.selection)
        }
    }

    @Test
    fun notATaskChangesNothing() {
        for (line in listOf("- [] nope", "- [ ]nope", "[ ] nope", "plain", "")) {
            assertTrue(line, ToggleTaskCheckbox(0).apply(line, TextSelection.caret(0)).isNoOp)
        }
    }
}
