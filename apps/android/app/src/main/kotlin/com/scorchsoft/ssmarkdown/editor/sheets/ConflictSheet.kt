package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.ConflictSummary
import com.scorchsoft.ssmarkdown.editor.EditorAction

/**
 * "<file> changed since your unsaved edits" (ux-spec "File changed outside
 * the app"; J5). Nothing is applied until a choice is made; dismissing it
 * keeps the edits, leaves autosave paused and the banner in place.
 */
@Composable
fun ConflictSheet(
    fileName: String,
    summary: ConflictSummary,
    onAction: (EditorAction) -> Unit,
    historyOn: Boolean = true,
) {
    SSSheet(onDismiss = { onAction(EditorAction.DismissOverlay) }) {
        SheetTitle(stringResource(R.string.conflict_title, fileName))
        SheetBody(stringResource(R.string.conflict_body))
        Summary(summary)
        if (summary.canMerge) {
            val hint =
                if (summary.overlaps == 0) {
                    stringResource(R.string.conflict_merge_clean)
                } else {
                    pluralStringResource(R.plurals.conflict_merge_hint, summary.overlaps, summary.overlaps)
                }
            SheetButton(
                stringResource(R.string.conflict_merge),
                Emphasis.Ink,
                { onAction(EditorAction.MergeMine) },
                hint,
            )
        } else {
            SheetBody(stringResource(R.string.conflict_no_base), muted = true)
        }
        SheetButton(
            stringResource(R.string.conflict_compare),
            Emphasis.Outline,
            { onAction(EditorAction.CompareConflict) },
        )
        SheetButton(
            stringResource(R.string.conflict_keep_theirs),
            Emphasis.Outline,
            { onAction(EditorAction.KeepTheirs) },
            stringResource(
                if (historyOn) R.string.conflict_keep_theirs_hint else R.string.conflict_keep_theirs_hint_history_off,
            ),
        )
        SheetButton(stringResource(R.string.conflict_use_mine), Emphasis.Quiet, { onAction(EditorAction.AskUseMine) })
    }
}

/** The two sides in numbers: what the other app changed, and what yours would merge. */
@Composable
private fun Summary(summary: ConflictSummary) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.conflict_theirs), style = SS.type.bodyStrong, color = SS.colors.ink)
        Text(
            pluralStringResource(
                R.plurals.conflict_theirs_lines,
                summary.theirsRemoved,
                summary.theirsAdded,
                summary.theirsRemoved,
            ),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
        )
        Text(
            stringResource(R.string.conflict_mine),
            style = SS.type.bodyStrong,
            color = SS.colors.ink,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            pluralStringResource(
                R.plurals.conflict_mine_changes,
                summary.mineChanges,
                summary.mineChanges,
            ) + " · " +
                pluralStringResource(
                    R.plurals.conflict_merge_summary,
                    summary.overlaps,
                    summary.cleanMerges,
                    summary.overlaps,
                ),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
        )
    }
}

/** "Use my version…" asks first: it replaces the other app's change. */
@Composable
fun UseMineDialog(
    onAction: (EditorAction) -> Unit,
    historyOn: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { onAction(EditorAction.DismissOverlay) },
        containerColor = SS.colors.chrome,
        title = { Text(stringResource(R.string.use_mine_title), style = SS.type.title, color = SS.colors.ink) },
        text = {
            val body = if (historyOn) R.string.use_mine_body else R.string.use_mine_body_history_off
            Text(stringResource(body), style = SS.type.body, color = SS.colors.ink)
        },
        confirmButton = {
            TextButton(onClick = { onAction(EditorAction.ConfirmUseMine) }) {
                Text(stringResource(R.string.use_mine_confirm), style = SS.type.label, color = SS.colors.needsYou)
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(EditorAction.DismissOverlay) }) {
                Text(stringResource(R.string.cancel), style = SS.type.label, color = SS.colors.ink)
            }
        },
    )
}
