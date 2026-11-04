package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.media.MediaMenuAction
import com.scorchsoft.ssmarkdown.preview.bridge.LinkTarget
import com.scorchsoft.ssmarkdown.preview.bridge.MediaKind
import com.scorchsoft.ssmarkdown.preview.bridge.TableAlign
import com.scorchsoft.ssmarkdown.preview.bridge.TableOp

/**
 * Gestures rule 3: the link chip, Open · Copy link · Edit link. A blocked
 * link says so instead of opening; Edit link only when Preview is editable.
 */
@Composable
fun LinkChipSheet(
    chip: EditorOverlay.LinkChip,
    onAction: (EditorAction) -> Unit,
) {
    SSSheet(onDismiss = { onAction(EditorAction.DismissOverlay) }) {
        val target = chip.target
        val shown =
            when (target) {
                is LinkTarget.External -> target.url
                is LinkTarget.Relative -> target.path
                LinkTarget.Blocked -> stringResource(R.string.link_blocked)
            }
        SheetBody(shown, muted = true)
        if (target != LinkTarget.Blocked) {
            SheetButton(stringResource(R.string.link_open), Emphasis.Ink, { onAction(EditorAction.OpenLink) })
        }
        if (target is LinkTarget.External) {
            SheetButton(stringResource(R.string.link_copy), Emphasis.Outline, { onAction(EditorAction.CopyLink) })
        }
        if (chip.canEdit) {
            SheetButton(stringResource(R.string.link_edit), Emphasis.Outline, { onAction(EditorAction.EditLink) })
        }
    }
}

/** The Mermaid sheet (ux-spec "Mermaid"; mockup Mermaid). Pictures get the same rows, minus SVG. */
@Composable
fun MediaMenuSheet(
    menu: EditorOverlay.MediaMenu,
    onAction: (EditorAction) -> Unit,
) {
    val act = { action: MediaMenuAction -> onAction(EditorAction.MediaMenu(action)) }
    val diagram = menu.kind == MediaKind.DIAGRAM
    SSSheet(onDismiss = { onAction(EditorAction.DismissOverlay) }) {
        SheetTitle(menu.title)
        SheetBody(
            stringResource(if (diagram) R.string.media_kind_diagram else R.string.media_kind_picture),
            muted = true,
        )
        SheetButton(
            stringResource(R.string.media_view),
            Emphasis.Ink,
            { act(MediaMenuAction.ViewFullScreen) },
            hint = stringResource(R.string.media_view_hint),
        )
        SheetButton(
            stringResource(R.string.media_save_png),
            Emphasis.Outline,
            { act(MediaMenuAction.SavePng) },
            hint = if (diagram) stringResource(R.string.media_save_png_hint, menu.pngScale) else null,
        )
        if (diagram) {
            SheetButton(
                stringResource(R.string.media_save_svg),
                Emphasis.Outline,
                { act(MediaMenuAction.SaveSvg) },
            )
        }
        SheetButton(stringResource(R.string.media_copy), Emphasis.Outline, { act(MediaMenuAction.CopyImage) })
        SheetButton(stringResource(R.string.media_share), Emphasis.Outline, { act(MediaMenuAction.Share) })
        SheetButton(stringResource(R.string.media_source), Emphasis.Quiet, { act(MediaMenuAction.ShowSource) })
    }
}

/** The table toolbar's ⋯ (ux-spec "Tables"): insert, move, align and delete, in the table's own style. */
@Composable
fun TableMenuSheet(onAction: (EditorAction) -> Unit) {
    val op = { value: TableOp -> onAction(EditorAction.Table(value)) }
    SSSheet(onDismiss = { onAction(EditorAction.DismissOverlay) }) {
        SheetTitle(stringResource(R.string.table_title))
        SheetButton(stringResource(R.string.table_add_row), Emphasis.Outline, { op(TableOp.AddRow) })
        SheetButton(stringResource(R.string.table_add_column), Emphasis.Outline, { op(TableOp.AddColumn) })
        SheetButton(stringResource(R.string.table_row_up), Emphasis.Outline, { op(TableOp.MoveRow(-1)) })
        SheetButton(stringResource(R.string.table_row_down), Emphasis.Outline, { op(TableOp.MoveRow(1)) })
        SheetButton(stringResource(R.string.table_column_left), Emphasis.Outline, { op(TableOp.MoveColumn(-1)) })
        SheetButton(stringResource(R.string.table_column_right), Emphasis.Outline, { op(TableOp.MoveColumn(1)) })
        SheetButton(stringResource(R.string.table_align_left), Emphasis.Outline, { op(TableOp.Align(TableAlign.LEFT)) })
        SheetButton(
            stringResource(R.string.table_align_center),
            Emphasis.Outline,
            { op(TableOp.Align(TableAlign.CENTER)) },
        )
        SheetButton(
            stringResource(R.string.table_align_right),
            Emphasis.Outline,
            { op(TableOp.Align(TableAlign.RIGHT)) },
        )
        SheetButton(stringResource(R.string.table_delete_row), Emphasis.Quiet, { op(TableOp.DeleteRow) })
        SheetButton(stringResource(R.string.table_delete_column), Emphasis.Quiet, { op(TableOp.DeleteColumn) })
        // Asks first: EditorOverlay.ConfirmDeleteTable.
        SheetButton(stringResource(R.string.table_delete_table), Emphasis.Quiet, { op(TableOp.DeleteTable) })
    }
}
