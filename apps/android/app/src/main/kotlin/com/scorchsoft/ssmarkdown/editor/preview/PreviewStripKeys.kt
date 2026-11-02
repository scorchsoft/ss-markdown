package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.markdown.edit.BlockType
import com.scorchsoft.ssmarkdown.preview.bridge.TableOp
import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import com.scorchsoft.ssmarkdown.ui.strip.BlockChoice
import com.scorchsoft.ssmarkdown.ui.strip.StripKey
import com.scorchsoft.ssmarkdown.ui.strip.TableAction

/**
 * The formatting strip in Preview (ux-spec "Editing in Preview"): the
 * same keys, sent to the page as format actions so they apply to the
 * rendered selection's source range in the document's own style.
 */
object PreviewStripKeys {
    /** The page's table op for each entry of the strip's table menu: every entry does something. */
    fun tableOp(action: TableAction): TableOp =
        when (action) {
            TableAction.AddRowBelow -> TableOp.AddRow
            TableAction.AddRowAbove -> TableOp.AddRowAbove
            TableAction.AddColumnRight -> TableOp.AddColumn
            TableAction.AddColumnLeft -> TableOp.AddColumnLeft
            TableAction.MoveRowUp -> TableOp.MoveRow(-1)
            TableAction.MoveRowDown -> TableOp.MoveRow(1)
            TableAction.MoveColumnLeft -> TableOp.MoveColumn(-1)
            TableAction.MoveColumnRight -> TableOp.MoveColumn(1)
            TableAction.DeleteRow -> TableOp.DeleteRow
            TableAction.DeleteColumn -> TableOp.DeleteColumn
            TableAction.DeleteTable -> TableOp.DeleteTable
        }

    /** The page's format action for a ¶ menu choice: the same conversion Code runs. */
    fun blockAction(choice: BlockChoice): FormatAction =
        when (choice) {
            BlockChoice.Paragraph -> FormatAction.BLOCK_PARAGRAPH
            BlockChoice.Heading1 -> FormatAction.BLOCK_HEADING_1
            BlockChoice.Heading2 -> FormatAction.BLOCK_HEADING_2
            BlockChoice.Heading3 -> FormatAction.BLOCK_HEADING_3
            BlockChoice.Bullet -> FormatAction.BLOCK_BULLET
            BlockChoice.Numbered -> FormatAction.BLOCK_NUMBERED
            BlockChoice.Task -> FormatAction.BLOCK_TASK
            BlockChoice.Quote -> FormatAction.BLOCK_QUOTE
            BlockChoice.CodeBlock -> FormatAction.BLOCK_CODE
        }

    /** core:markdown's type for a ¶ menu choice, and back (for the menu's tick). */
    fun blockType(choice: BlockChoice): BlockType = BlockType.valueOf(choice.name)

    fun choiceFor(type: BlockType?): BlockChoice? = type?.let { BlockChoice.valueOf(it.name) }

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

            // The page answers with its selection; the host opens the picker (PictureController).
            StripKey.Image -> FormatAction.IMAGE

            StripKey.BulletList -> FormatAction.BULLET

            StripKey.Task -> FormatAction.TASK

            StripKey.Quote -> FormatAction.QUOTE

            else -> null
        }
}
