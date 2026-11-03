package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.editor.StripController
import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand
import com.scorchsoft.ssmarkdown.markdown.edit.FenceStates
import com.scorchsoft.ssmarkdown.markdown.edit.FormatState
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.bridge.TableOp
import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import com.scorchsoft.ssmarkdown.preview.editing.PreviewFormatter
import com.scorchsoft.ssmarkdown.ui.strip.BlockChoice
import com.scorchsoft.ssmarkdown.ui.strip.StripKey
import com.scorchsoft.ssmarkdown.ui.strip.StripLayout
import com.scorchsoft.ssmarkdown.ui.strip.TableAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The strip's table menu in Preview: every entry it shows reaches the page
 * as its own table op. In 0.2.0 "Add row above", "Add column left"
 * and "Delete table" mapped to nothing, so a tap did nothing at all.
 */
class PreviewStripKeysTest {
    @Test
    fun `every table menu entry sends its own op`() {
        val ops = TableAction.entries.associateWith { PreviewStripKeys.tableOp(it) }
        assertEquals(TableAction.entries.size, ops.values.toSet().size)
        assertEquals(TableOp.DeleteRow, ops[TableAction.DeleteRow])
        assertEquals(TableOp.AddRowAbove, ops[TableAction.AddRowAbove])
        assertEquals(TableOp.AddColumnLeft, ops[TableAction.AddColumnLeft])
        assertEquals(TableOp.DeleteTable, ops[TableAction.DeleteTable])
        assertEquals(TableOp.MoveRow(-1), ops[TableAction.MoveRowUp])
    }

    @Test
    fun `every key Preview's strip shows does something, the list and indent keys as Code's commands`() {
        val handledElsewhere = setOf(StripKey.Table, StripKey.BlockStyle)
        for (key in StripLayout.previewKeys - handledElsewhere) {
            assertNotNull("$key", PreviewStripKeys.actionFor(key, inHeading = false))
        }
        assertEquals(FormatAction.NUMBERED, PreviewStripKeys.actionFor(StripKey.NumberedList, inHeading = false))
        assertEquals(FormatAction.OUTDENT, PreviewStripKeys.actionFor(StripKey.Outdent, inHeading = false))
        assertEquals(FormatAction.INDENT, PreviewStripKeys.actionFor(StripKey.Indent, inHeading = false))
        // Outdent and Indent sit together, ← then →, in both strips.
        for (keys in listOf(StripLayout.codeKeys, StripLayout.previewKeys)) {
            assertEquals(keys.indexOf(StripKey.Outdent) + 1, keys.indexOf(StripKey.Indent))
        }
    }

    @Test
    fun `the numbered key shows pressed in a numbered list, Indent is disabled on a paragraph and ¶ in a table`() {
        val numbered = FormatState.at("1. a", TextSelection.caret(4))
        assertEquals(setOf(StripKey.NumberedList), StripController.pressedKeys(numbered))
        assertEquals(emptySet<StripKey>(), StripController.disabledKeys(numbered))
        val paragraph = FormatState.at("plain", TextSelection.caret(2))
        assertEquals(setOf(StripKey.Indent), StripController.disabledKeys(paragraph))
        val code = "```\nx\n```"
        val inCode = FormatState.at(code, TextSelection.caret(5), FenceStates.scan(code))
        assertEquals(emptySet<StripKey>(), StripController.disabledKeys(inCode))
        val table = FormatState.at("| a |\n| - |\n| 1 |", TextSelection.caret(14))
        assertEquals(setOf(StripKey.Indent, StripKey.BlockStyle), StripController.disabledKeys(table))
    }

    @Test
    fun `every block menu choice runs core markdown's conversion to that type, in Preview as in Code`() {
        for (choice in BlockChoice.entries) {
            val type = PreviewStripKeys.blockType(choice)
            assertEquals(choice.name, type.name)
            val text = "Intro\n\n- [x] an item\n\nOutro"
            val caret = TextSelection.caret(text.indexOf("item"))
            val preview =
                PreviewFormatter.format(
                    text,
                    caret,
                    PreviewStripKeys.blockAction(choice),
                    DocumentStyle.DEFAULT,
                )
            val code = EditCommand.SetBlockType(type).apply(text, caret)
            val applied =
                preview
                    ?.edits
                    ?.sortedByDescending {
                        it.from
                    }?.fold(text) { t, e -> t.replaceRange(e.from, e.to, e.insert) }
            assertEquals(choice.name, code.applyTo(text), applied)
            assertEquals(choice, PreviewStripKeys.choiceFor(type))
        }
        assertEquals(null, PreviewStripKeys.choiceFor(null))
    }
}
