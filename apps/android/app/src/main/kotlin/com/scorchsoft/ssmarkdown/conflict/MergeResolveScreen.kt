package com.scorchsoft.ssmarkdown.conflict

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.sheets.Emphasis
import com.scorchsoft.ssmarkdown.editor.sheets.SheetButton
import com.scorchsoft.ssmarkdown.ui.AppIcons
import com.scorchsoft.ssmarkdown.ui.NoteText
import com.scorchsoft.ssmarkdown.ui.SecondaryScreen

private val BlockShape = RoundedCornerShape(8.dp)

/** What MergeResolve can do. */
sealed interface MergeAction {
    data class Choose(
        val choice: OverlapChoice,
    ) : MergeAction

    data class Show(
        val index: Int,
    ) : MergeAction

    data class EditByHand(
        val editing: Boolean,
    ) : MergeAction

    data object Done : MergeAction

    data object Back : MergeAction
}

/**
 * MergeResolve (the board): one overlap at a time, the file's lines and
 * yours, and four answers. Nothing is written until Done and then Save.
 */
@Composable
fun MergeResolveScreen(
    state: MergeUiState,
    onAction: (MergeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryScreen(stringResource(R.string.merge_title, state.fileName), { onAction(MergeAction.Back) }, modifier) {
        Text(
            pluralStringResource(R.plurals.merge_summary, state.overlaps.size, state.cleanMerges, state.overlaps.size),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
            modifier = Modifier.padding(16.dp),
        )
        state.overlaps.getOrNull(state.current)?.let { Overlap(it, state, onAction) }
        NoteText(stringResource(if (state.historyOn) R.string.merge_note else R.string.merge_note_history_off))
        Column(Modifier.padding(horizontal = 16.dp)) {
            SheetButton(
                pluralStringResource(R.plurals.merge_done_left, state.remaining, state.remaining).takeIf {
                    state.remaining >
                        0
                }
                    ?: stringResource(R.string.merge_done),
                Emphasis.Ink,
                { onAction(MergeAction.Done) },
            )
        }
    }
}

@Composable
private fun Overlap(
    overlap: Overlap,
    state: MergeUiState,
    onAction: (MergeAction) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row {
            Text(
                overlap.section?.let { stringResource(R.string.merge_where_section, overlap.line, it) }
                    ?: stringResource(R.string.merge_where, overlap.line),
                style = SS.type.body,
                color = SS.colors.ink,
                modifier = Modifier.weight(1f),
            )
            IconButton(enabled = state.current > 0, onClick = { onAction(MergeAction.Show(state.current - 1)) }) {
                Icon(AppIcons.chevronUp, stringResource(R.string.merge_previous), tint = SS.colors.ink)
            }
            IconButton(enabled = state.current < state.overlaps.lastIndex, onClick = {
                onAction(
                    MergeAction.Show(state.current + 1),
                )
            }) {
                Icon(AppIcons.chevronDown, stringResource(R.string.merge_next), tint = SS.colors.ink)
            }
        }
        Side(stringResource(R.string.merge_theirs), overlap.theirs, removed = true)
        Side(stringResource(R.string.merge_mine), overlap.mine, removed = false)
        if (state.editing) {
            HandEdit(overlap, onAction)
        } else {
            SheetButton(stringResource(R.string.merge_use_theirs), Emphasis.Outline, {
                onAction(MergeAction.Choose(OverlapChoice.Theirs))
            })
            SheetButton(stringResource(R.string.merge_use_mine), Emphasis.Outline, {
                onAction(MergeAction.Choose(OverlapChoice.Mine))
            })
            SheetButton(stringResource(R.string.merge_keep_both), Emphasis.Outline, {
                onAction(MergeAction.Choose(OverlapChoice.Both))
            })
            SheetButton(stringResource(R.string.merge_edit), Emphasis.Quiet, { onAction(MergeAction.EditByHand(true)) })
        }
    }
}

/** The file's side is struck-through grey, yours info blue: never red (quality-bar "Diff"). */
@Composable
private fun Side(
    label: String,
    text: String,
    removed: Boolean,
) {
    Text(
        label,
        style = SS.type.label,
        color = SS.colors.inkMuted,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    Text(
        text.trimEnd('\n'),
        style = SS.type.code.copy(textDecoration = if (removed) TextDecoration.LineThrough else null),
        color = if (removed) SS.colors.inkMuted else SS.colors.info,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (removed) SS.colors.segmentTrack else SS.colors.infoBg,
                    BlockShape,
                ).padding(10.dp),
    )
}

@Composable
private fun HandEdit(
    overlap: Overlap,
    onAction: (MergeAction) -> Unit,
) {
    var text by rememberSaveable(overlap.line) { mutableStateOf(overlap.mine.trimEnd('\n')) }
    OutlinedTextField(value = text, onValueChange = {
        text = it
    }, textStyle = SS.type.code, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    SheetButton(stringResource(R.string.merge_edit_apply), Emphasis.Ink, {
        onAction(MergeAction.Choose(OverlapChoice.Edited(text)))
    })
}
