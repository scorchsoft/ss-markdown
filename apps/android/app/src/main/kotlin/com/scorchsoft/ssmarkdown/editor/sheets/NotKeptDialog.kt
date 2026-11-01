package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.NotKeptStep
import com.scorchsoft.ssmarkdown.files.document.CheckpointResult

/**
 * History could not keep a copy of the edits a step would drop, so
 * nothing has happened yet (R2-DS-1). Worded from History's answer, not
 * the setting. The safe choice, keeping the edits, is the dismiss.
 */
@Composable
fun NotKeptDialog(
    overlay: EditorOverlay.NotKept,
    onAction: (EditorAction) -> Unit,
) {
    val off = overlay.checkpoint == CheckpointResult.Off
    val goOn = if (overlay.step is NotKeptStep.FinishMerge) R.string.not_kept_go_on_merge else R.string.not_kept_go_on
    AlertDialog(
        onDismissRequest = { onAction(EditorAction.DismissOverlay) },
        containerColor = SS.colors.chrome,
        title = {
            Text(
                stringResource(if (off) R.string.not_kept_title_off else R.string.not_kept_title_failed),
                style = SS.type.title,
                color = SS.colors.ink,
            )
        },
        text = {
            Text(
                stringResource(if (off) R.string.not_kept_body_off else R.string.not_kept_body_failed),
                style = SS.type.body,
                color = SS.colors.ink,
            )
        },
        confirmButton = {
            TextButton(onClick = { onAction(EditorAction.DismissOverlay) }) {
                Text(stringResource(R.string.not_kept_keep), style = SS.type.label, color = SS.colors.ink)
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(EditorAction.ConfirmNotKept) }) {
                Text(stringResource(goOn), style = SS.type.label, color = SS.colors.needsYou)
            }
        },
    )
}
