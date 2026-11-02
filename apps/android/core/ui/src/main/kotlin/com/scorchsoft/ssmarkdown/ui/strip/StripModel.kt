package com.scorchsoft.ssmarkdown.ui.strip

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.core.ui.R

/** Which editor the strip serves: Code has raw syntax to type, Preview does not. */
enum class StripMode { Code, Preview }

/** The strip's two pages. Only Code has symbols (docs/ux-spec.md, "Bottom bar"). */
enum class StripPage { Formatting, Symbols }

/** A cursor key on the symbols page; held down, it repeats. */
enum class CursorStep { Left, Right }

/**
 * Every key the strip can show. [label] is what TalkBack reads; [toggles]
 * marks the keys that show a pressed state (B inside bold text, and so on).
 */
enum class StripKey(
    @param:StringRes val label: Int,
    val toggles: Boolean = false,
) {
    Heading(R.string.strip_heading, toggles = true),
    BlockStyle(R.string.strip_block_style),
    Bold(R.string.strip_bold, toggles = true),
    Italic(R.string.strip_italic, toggles = true),
    BulletList(R.string.strip_bullet_list, toggles = true),
    Task(R.string.strip_task, toggles = true),
    Code(R.string.strip_code, toggles = true),
    Link(R.string.strip_link),
    Indent(R.string.strip_indent),
    Table(R.string.strip_table),

    /** Opens the picture picker; the picture is linked by a relative path (ux-spec "Pictures"). */
    Image(R.string.strip_image),
    Quote(R.string.strip_quote, toggles = true),
    Redo(R.string.strip_redo),
    Outdent(R.string.strip_outdent),
    Undo(R.string.strip_undo),
    Symbols(R.string.strip_symbols),
    Done(R.string.strip_done),

    /** Table mode: moves the caret to the previous cell. */
    PrevCell(R.string.strip_prev_cell),

    /** Table mode: moves the caret to the next cell; on the last cell the editor adds a row. */
    NextCell(R.string.strip_next_cell),

    /** Table mode: opens the row and column actions ([TableAction]). */
    TableMenu(R.string.strip_table_menu),
}

/**
 * The row and column actions behind the table-mode overflow key, in menu
 * order (docs/ux-spec.md, "Tables": insert after and before, move, delete).
 * The editor applies them to the table the caret is in.
 */
enum class TableAction(
    @param:StringRes val label: Int,
) {
    AddRowBelow(R.string.table_add_row_below),
    AddRowAbove(R.string.table_add_row_above),
    AddColumnRight(R.string.table_add_column_right),
    AddColumnLeft(R.string.table_add_column_left),
    MoveRowUp(R.string.table_move_row_up),
    MoveRowDown(R.string.table_move_row_down),
    MoveColumnLeft(R.string.table_move_column_left),
    MoveColumnRight(R.string.table_move_column_right),
    DeleteRow(R.string.table_delete_row),
    DeleteColumn(R.string.table_delete_column),
    DeleteTable(R.string.table_delete_table),
}

/**
 * The block types behind the ¶ key, in menu order (docs/ux-spec.md,
 * "Formatting strip"). The editor converts every block in the selection
 * to the one chosen, in Code and in Preview alike.
 */
enum class BlockChoice(
    @param:StringRes val label: Int,
) {
    Paragraph(R.string.block_paragraph),
    Heading1(R.string.block_heading_1),
    Heading2(R.string.block_heading_2),
    Heading3(R.string.block_heading_3),
    Bullet(R.string.block_bullet),
    Numbered(R.string.block_numbered),
    Task(R.string.block_task),
    Quote(R.string.block_quote),
    CodeBlock(R.string.block_code),
}

/** A key on the symbols page: the [text] it types and what TalkBack calls it. */
@Immutable
data class SymbolKey(
    val text: String,
    @param:StringRes val label: Int,
)

/**
 * The filename, unsaved dot and Save button the strip carries in
 * landscape, when the header steps aside. Because the header is gone, the
 * strip must say everything it would have said (UX-9): [save] follows the
 * header's Save-state table, and [status] carries the header's second
 * line when it matters ("Couldn't save · storage full", "Read-only").
 */
@Immutable
data class StripDocument(
    val name: String,
    val isDirty: Boolean,
    val save: StripSave = if (isDirty) StripSave.Save else StripSave.Saved,
    /** The header's status line, shown under the name; null when there is nothing to say. */
    val status: String? = null,
    /** [status] is a failure, drawn in the error red. */
    val statusIsError: Boolean = false,
)

/**
 * The strip's Save button, one per row of the header's Save-state table
 * (docs/ux-spec.md). [filled] states are the red pill; the others are an
 * ink outline, because a draft or a read-only file is already safe.
 */
enum class StripSave(
    @param:StringRes val label: Int,
    val filled: Boolean,
) {
    /** The muted tick; still the Save button. */
    Saved(R.string.strip_saved, filled = false),
    Save(R.string.strip_save, filled = true),

    /** A draft or a read-only file: Save as…, outlined, not red. */
    SaveAs(R.string.strip_save_as, filled = false),

    /** The file can't be written where it is (moved, permission gone): red Save as…. */
    SaveAsRequired(R.string.strip_save_as, filled = true),
    Retry(R.string.strip_retry, filled = true),

    /** A save is running: a spinner, and the button does nothing. */
    Saving(R.string.strip_saving, filled = false),
}

/**
 * Everything the strip draws. The strip is stateless: the editor owns
 * this, changes it in response to the callbacks, and passes it back.
 */
@Immutable
data class FormattingStripState(
    val mode: StripMode = StripMode.Code,
    val page: StripPage = StripPage.Formatting,
    /** Toggle keys to show pressed; keys whose [StripKey.toggles] is false are ignored. */
    val pressed: Set<StripKey> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Set in landscape with the keyboard up; null in portrait, where the header carries these. */
    val document: StripDocument? = null,
    /**
     * The caret is in a table. The strip's right end becomes ‹ Prev cell /
     * Next cell ›, with the row and column actions behind ⋯.
     */
    val inTable: Boolean = false,
    /** The caret's block type, ticked in the ¶ menu; null when it is none of [BlockChoice]. */
    val block: BlockChoice? = BlockChoice.Paragraph,
)

/**
 * Which keys go where. Pure, so the order the spec fixes is tested
 * without a UI (docs/ux-spec.md, "More decisions from review 3").
 */
object StripLayout {
    /** Code mode's scrolling keys, in the spec's order. Undo and `{ }` are pinned, not listed. */
    val codeKeys: List<StripKey> =
        listOf(
            StripKey.BlockStyle,
            StripKey.Bold,
            StripKey.Italic,
            StripKey.BulletList,
            StripKey.Task,
            StripKey.Code,
            StripKey.Link,
            StripKey.Indent,
            StripKey.Table,
            StripKey.Image,
            StripKey.Quote,
            StripKey.Redo,
            StripKey.Outdent,
        )

    /** Preview's keys: formatting only, since rendered text has no syntax to type ("Editing in Preview"). */
    val previewKeys: List<StripKey> =
        listOf(
            StripKey.BlockStyle,
            StripKey.Bold,
            StripKey.Italic,
            StripKey.Code,
            StripKey.Link,
            StripKey.BulletList,
            StripKey.Table,
            StripKey.Image,
        )

    /** The symbols page, in the spec's order. The cursor keys follow them. */
    val symbols: List<SymbolKey> =
        listOf(
            SymbolKey("`", R.string.symbol_backtick),
            SymbolKey("[", R.string.symbol_open_bracket),
            SymbolKey("]", R.string.symbol_close_bracket),
            SymbolKey("(", R.string.symbol_open_parenthesis),
            SymbolKey(")", R.string.symbol_close_parenthesis),
            SymbolKey("#", R.string.symbol_hash),
            SymbolKey("*", R.string.symbol_asterisk),
            SymbolKey(">", R.string.symbol_greater_than),
            SymbolKey("|", R.string.symbol_pipe),
            SymbolKey("-", R.string.symbol_hyphen),
            SymbolKey("=", R.string.symbol_equals),
            SymbolKey("!", R.string.symbol_exclamation),
        )

    fun scrollingKeys(mode: StripMode): List<StripKey> = if (mode == StripMode.Code) codeKeys else previewKeys

    /**
     * Table mode's keys, pinned just before Undo so they never scroll away:
     * ⋯ (row and column actions), then ‹ Prev cell and Next cell ›, each a
     * full 48 dp key. Empty outside a table or on the symbols page.
     */
    fun tableKeys(state: FormattingStripState): List<StripKey> =
        if (state.inTable && state.page == StripPage.Formatting) {
            listOf(StripKey.TableMenu, StripKey.PrevCell, StripKey.NextCell)
        } else {
            emptyList()
        }

    /** The keys pinned at the right end: Undo, then `{ }` in Code or Done in Preview. */
    fun pinnedKeys(mode: StripMode): List<StripKey> =
        listOf(StripKey.Undo, if (mode == StripMode.Code) StripKey.Symbols else StripKey.Done)
}

/**
 * How the page changes, kept beside the layout so the editor and the
 * tests share one rule. There is no hidden lock: `{ }` flips the page,
 * symbols keep it, and the first letter typed flips it back.
 */
object StripPaging {
    /** The page after [key] is tapped on [page]. */
    fun afterKey(
        page: StripPage,
        key: StripKey,
    ): StripPage =
        when {
            key == StripKey.Symbols && page == StripPage.Formatting -> StripPage.Symbols
            key == StripKey.Symbols -> StripPage.Formatting
            else -> page
        }

    /** The page after the keyboard typed [text] on [page]. Symbols and digits keep it; a letter ends it. */
    fun afterTyped(
        page: StripPage,
        text: CharSequence,
    ): StripPage = if (text.any { it.isLetter() }) StripPage.Formatting else page
}

/** Labels for keys that are not [StripKey]s. */
internal object StripLabels {
    @StringRes val cursorLeft: Int = R.string.strip_cursor_left

    @StringRes val cursorRight: Int = R.string.strip_cursor_right

    @StringRes val toolbar: Int = R.string.strip_toolbar

    @StringRes val unsaved: Int = R.string.strip_unsaved
}
