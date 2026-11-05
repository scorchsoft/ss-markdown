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
        // Table was "handled elsewhere" here, and was not: in Preview it did nothing (Andrew, 2026-11-04).
        val handledElsewhere = setOf(StripKey.BlockStyle)
        for (key in StripLayout.previewKeys - handledElsewhere) {
            assertNotNull("$key", PreviewStripKeys.actionFor(key, inHeading = false))
        }
        assertEquals(FormatAction.NUMBERED, PreviewStripKeys.actionFor(StripKey.NumberedList, inHeading = false))
        assertEquals(FormatAction.OUTDENT, PreviewStripKeys.actionFor(StripKey.Outdent, inHeading = false))
        assertEquals(FormatAction.INDENT, PreviewStripKeys.actionFor(StripKey.Indent, inHeading = false))
        assertEquals(FormatAction.TABLE, PreviewStripKeys.actionFor(StripKey.Table, inHeading = false))
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

    /** Andrew, 2026-11-04: the Table key did nothing in Preview. It inserts Code's table at the page's caret. */
    @Test
    fun `Table in Preview inserts the table Code would, after the caret's block, and never inside a cell`() {
        val text = "Intro paragraph.\n\n## Next"
        val caret = TextSelection(5, 5)
        val result = PreviewFormatter.format(text, caret, FormatAction.TABLE, DocumentStyle.DEFAULT)
        assertNotNull(result)
        val code = EditCommand.InsertTable().apply(text, caret, DocumentStyle.DEFAULT)
        var after = text
        for (e in result!!.edits.sortedByDescending { it.from }) after = after.replaceRange(e.from, e.to, e.insert)
        assertEquals(code.applyTo(text), after)
        assertEquals(code.selection, result.selection)
        assertEquals(
            null,
            PreviewFormatter.format(text, caret, FormatAction.TABLE, DocumentStyle.DEFAULT, inTable = true),
        )
    }
}
