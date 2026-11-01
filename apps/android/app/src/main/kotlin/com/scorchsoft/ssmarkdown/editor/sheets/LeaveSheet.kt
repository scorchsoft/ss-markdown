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
