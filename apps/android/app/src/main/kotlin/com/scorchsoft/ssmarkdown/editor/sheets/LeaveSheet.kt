package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.editor.EditorAction

/**
 * Leaving with unsaved edits (robustness §6): Save / Don't save / Keep
 * editing. Shown instead of the predictive-back preview. "Don't save"
 * keeps a copy in History, so it can be undone; with History off
 * ([historyOn] false) the note says it cannot (DS-9).
 *
 * For an untitled draft left by New or Open ([DraftLeaveSheet]) nothing is
 * lost either way, but the user should know where it went: Save as… (then
 * on to the new or opened file), Keep as draft, Delete draft (with Undo)
 * or Keep editing.
 */
@Composable
fun LeaveSheet(
    fileName: String,
    onAction: (EditorAction) -> Unit,
    historyOn: Boolean = true,
) {
    SSSheet(onDismiss = { onAction(EditorAction.KeepEditing) }) {
        SheetTitle(stringResource(R.string.leave_title, fileName))
        SheetButton(stringResource(R.string.leave_save), Emphasis.NeedsYou, { onAction(EditorAction.LeaveSave) })
        SheetButton(stringResource(R.string.leave_discard), Emphasis.Outline, { onAction(EditorAction.LeaveDiscard) })
        SheetButton(stringResource(R.string.leave_keep), Emphasis.Quiet, { onAction(EditorAction.KeepEditing) })
        SheetBody(stringResource(if (historyOn) R.string.leave_note else R.string.leave_note_history_off), muted = true)
    }
}

/** New or Open from an untitled draft with words in it (Andrew, 2026-11-04). */
@Composable
fun DraftLeaveSheet(onAction: (EditorAction) -> Unit) {
    SSSheet(onDismiss = { onAction(EditorAction.KeepEditing) }) {
        SheetTitle(stringResource(R.string.leave_draft_title))
        SheetButton(stringResource(R.string.leave_draft_save_as), Emphasis.Ink, { onAction(EditorAction.LeaveSave) })
        SheetButton(
            stringResource(R.string.leave_draft_keep),
            Emphasis.Outline,
            { onAction(EditorAction.LeaveKeepDraft) },
        )
        SheetButton(
            stringResource(R.string.leave_draft_delete),
            Emphasis.Outline,
            { onAction(EditorAction.LeaveDiscard) },
        )
        SheetButton(stringResource(R.string.leave_keep), Emphasis.Quiet, { onAction(EditorAction.KeepEditing) })
        SheetBody(stringResource(R.string.leave_draft_note), muted = true)
    }
}
