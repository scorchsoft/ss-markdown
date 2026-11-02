package com.scorchsoft.ssmarkdown.preview.editing

import com.scorchsoft.ssmarkdown.markdown.edit.BlockType
import com.scorchsoft.ssmarkdown.markdown.edit.DocumentStyle
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand
import com.scorchsoft.ssmarkdown.markdown.edit.InlineFormat
import com.scorchsoft.ssmarkdown.markdown.edit.ListKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit

/**
 * The strip's formatting keys and block-style picker as Preview sends them
 * (`FormatAction` in `web/src/protocol.ts`). [wire] is the name on the bridge.
 */
enum class FormatAction(
    val wire: String,
    /** What the action runs; null for the history keys, which the host handles. */
    internal val command: EditCommand?,
    /** Changes whole lines (headings, lists, quotes): not allowed inside a table cell. */
    internal val block: Boolean = false,
) {
    BOLD("bold", EditCommand.ToggleInline(InlineFormat.Bold)),
    ITALIC("italic", EditCommand.ToggleInline(InlineFormat.Italic)),
    STRIKE("strike", EditCommand.ToggleInline(InlineFormat.Strikethrough)),
    CODE("code", EditCommand.ToggleInline(InlineFormat.Code)),
    LINK("link", EditCommand.InsertLink),
    BULLET("bullet", EditCommand.ToggleList(ListKind.Bullet), block = true),
    NUMBERED("numbered", EditCommand.ToggleList(ListKind.Numbered), block = true),
    TASK("task", EditCommand.ToggleList(ListKind.Task), block = true),
    QUOTE("quote", EditCommand.ToggleBlockquote, block = true),
    CODE_BLOCK("codeBlock", EditCommand.ToggleCodeBlock, block = true),
    PARAGRAPH("heading0", EditCommand.SetHeading(level = 0), block = true),
    HEADING_1("heading1", EditCommand.SetHeading(level = 1), block = true),
    HEADING_2("heading2", EditCommand.SetHeading(level = 2), block = true),
    HEADING_3("heading3", EditCommand.SetHeading(level = 3), block = true),
    HEADING_4("heading4", EditCommand.SetHeading(level = 4), block = true),
    HEADING_5("heading5", EditCommand.SetHeading(level = 5), block = true),
    HEADING_6("heading6", EditCommand.SetHeading(level = 6), block = true),

    /**
     * Enter on an empty list item in Preview: Code's own Enter, which ends
     * the list there (a nested item steps out one level), so both views
     * leave a list the same way.
     */
    LIST_EXIT("listExit", EditCommand.Newline, block = true),

    // The ¶ menu: each converts the selected blocks, replacing their old syntax (EditCommand.SetBlockType).
    BLOCK_PARAGRAPH("blockParagraph", EditCommand.SetBlockType(BlockType.Paragraph), block = true),
    BLOCK_HEADING_1("blockHeading1", EditCommand.SetBlockType(BlockType.Heading1), block = true),
    BLOCK_HEADING_2("blockHeading2", EditCommand.SetBlockType(BlockType.Heading2), block = true),
    BLOCK_HEADING_3("blockHeading3", EditCommand.SetBlockType(BlockType.Heading3), block = true),
    BLOCK_BULLET("blockBullet", EditCommand.SetBlockType(BlockType.Bullet), block = true),
    BLOCK_NUMBERED("blockNumbered", EditCommand.SetBlockType(BlockType.Numbered), block = true),
    BLOCK_TASK("blockTask", EditCommand.SetBlockType(BlockType.Task), block = true),
    BLOCK_QUOTE("blockQuote", EditCommand.SetBlockType(BlockType.Quote), block = true),
    BLOCK_CODE("blockCode", EditCommand.SetBlockType(BlockType.CodeBlock), block = true),

    /** Keyboard undo / redo inside Preview: the host's history, not a format. */
    UNDO("undo", command = null),
    REDO("redo", command = null),
    ;

    /** True for the history keys, which [PreviewFormatter] does not handle. */
    val isHistory: Boolean get() = command == null

    /** Lookup by wire name. */
    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(name: String): FormatAction? = byWire[name]
    }
}

/** A format applied: the minimal [edits] for the host's text and where the selection goes after them. */
data class PreviewFormatResult(
    /** Sorted, non-overlapping, relative to the text the command read. */
    val edits: List<TextEdit>,
    /** In the text after [edits]. */
    val selection: TextSelection,
)

/**
 * Runs a [FormatAction] with core:markdown's [EditCommand] on the source range
 * the page's inline source map gave for the selection. The command writes
 * markers in the document's own [DocumentStyle] and its ops are already
 * minimal, so bold in a `__strong__` file adds `__`, and nothing outside the
 * touched node changes (docs/ux-spec.md, "Editing in Preview").
 */
object PreviewFormatter {
    /**
     * Formats [selection] in [text]. Returns null for an action it does not
     * handle (history) or a line format inside a table cell, where it would
     * break the table.
     */
    fun format(
        text: CharSequence,
        selection: TextSelection,
        action: FormatAction,
        style: DocumentStyle,
        inTable: Boolean = false,
    ): PreviewFormatResult? {
        val command = action.command?.takeUnless { inTable && action.block }
        return command?.apply(text, selection, style)?.let { result ->
            val edits =
                result.ops
                    .map { TextEdit(it.offset, it.offset + it.deleted.length, it.inserted.toString()) }
                    .sortedBy { it.from }
            PreviewFormatResult(edits, result.selection)
        }
    }
}
