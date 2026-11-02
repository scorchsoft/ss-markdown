package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.bridge.TableOp
import com.scorchsoft.ssmarkdown.preview.editing.PreviewFormatter
import com.scorchsoft.ssmarkdown.ui.strip.BlockChoice
import com.scorchsoft.ssmarkdown.ui.strip.TableAction
import org.junit.Assert.assertEquals
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
