package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.preview.bridge.TableOp
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
}
