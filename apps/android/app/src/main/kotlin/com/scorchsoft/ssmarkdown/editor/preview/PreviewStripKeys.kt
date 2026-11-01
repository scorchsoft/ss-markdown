package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.preview.bridge.TableOp
import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import com.scorchsoft.ssmarkdown.ui.strip.StripKey
import com.scorchsoft.ssmarkdown.ui.strip.TableAction

/**
 * The formatting strip in Preview (ux-spec "Editing in Preview"): the
 * same keys, sent to the page as format actions so they apply to the
 * rendered selection's source range in the document's own style.
 */
object PreviewStripKeys {
    /**
     * The table sheet's op for the strip's table menu, or null for what the
     * page cannot do yet (insert above or to the left, delete the table).
     */
    fun tableOp(action: TableAction): TableOp? =
        when (action) {
            TableAction.AddRowBelow -> TableOp.AddRow
            TableAction.AddColumnRight -> TableOp.AddColumn
            TableAction.MoveRowUp -> TableOp.MoveRow(-1)
            TableAction.MoveRowDown -> TableOp.MoveRow(1)
            TableAction.MoveColumnLeft -> TableOp.MoveColumn(-1)
            TableAction.MoveColumnRight -> TableOp.MoveColumn(1)
            TableAction.DeleteRow -> TableOp.DeleteRow
            TableAction.DeleteColumn -> TableOp.DeleteColumn
            TableAction.AddRowAbove, TableAction.AddColumnLeft, TableAction.DeleteTable -> null
        }

    /** The format action behind [key], or null for keys Preview handles otherwise (undo, Done) or not at all. */
    fun actionFor(
        key: StripKey,
        inHeading: Boolean,
    ): FormatAction? =
        when (key) {
            // Heading toggles: a heading becomes a paragraph, anything else a second-level heading.
            StripKey.Heading, StripKey.BlockStyle -> if (inHeading) FormatAction.PARAGRAPH else FormatAction.HEADING_2

            StripKey.Bold -> FormatAction.BOLD

            StripKey.Italic -> FormatAction.ITALIC

            StripKey.Code -> FormatAction.CODE

            StripKey.Link -> FormatAction.LINK

            StripKey.BulletList -> FormatAction.BULLET

            StripKey.Task -> FormatAction.TASK

            StripKey.Quote -> FormatAction.QUOTE

            else -> null
        }
}
