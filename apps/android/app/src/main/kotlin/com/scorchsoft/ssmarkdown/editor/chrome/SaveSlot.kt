package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.SaveButton
import com.scorchsoft.ssmarkdown.editor.SaveStatusLine
import com.scorchsoft.ssmarkdown.ui.AppIcons
import com.scorchsoft.ssmarkdown.ui.strip.StripDocument
import com.scorchsoft.ssmarkdown.ui.strip.StripSave

/** The fixed Save slot: 96 dp in every state, sized for "Save as…", so Copy never moves (review 3). */
val SaveSlotWidth = 96.dp

private val PillShape = RoundedCornerShape(20.dp)
private val PillPadding = 14.dp
private val SpinnerSize = 16.dp

/**
 * The Save button in each state of the Save-state table. The pill is
 * right-aligned in the slot; the clean tick is right-aligned too, and it
 * *is* the Save button, so it is present in autosave as well. Long-press
 * offers Save as… · Autosave this file.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SaveSlot(
    button: SaveButton,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = saveLabel(button)
    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier =
            modifier
                .width(SaveSlotWidth)
                .height(SS.dimens.pillHitArea)
                .combinedClickable(
                    enabled = button != SaveButton.Saving,
                    role = Role.Button,
                    onClickLabel = label,
                    onLongClick = onLongPress,
                    onClick = onTap,
                ).semantics { contentDescription = label },
    ) {
        when (button) {
            SaveButton.AllSaved -> {
                Icon(
                    AppIcons.check,
                    contentDescription = null,
                    tint = SS.colors.inkMuted,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }

            SaveButton.Saving -> {
                SavingPill(label)
            }

            SaveButton.SaveAsDraft, SaveButton.SaveAsReadOnly -> {
                Pill(label, filled = false)
            }

            SaveButton.Save, SaveButton.SaveAsRequired, SaveButton.Retry -> {
                Pill(label, filled = true)
            }
        }
    }
}

@Composable
private fun saveLabel(button: SaveButton): String =
    stringResource(
        when (button) {
            SaveButton.AllSaved -> R.string.all_saved
            SaveButton.Save -> R.string.save
            SaveButton.Saving -> R.string.saving
            SaveButton.SaveAsDraft, SaveButton.SaveAsRequired, SaveButton.SaveAsReadOnly -> R.string.save_as
            SaveButton.Retry -> R.string.retry
        },
    )

@Composable
private fun Pill(
    label: String,
    filled: Boolean,
) {
    val colors = SS.colors
    val shape =
        if (filled) {
            Modifier.background(
                colors.needsYou,
                PillShape,
            )
        } else {
            Modifier.border(SS.dimens.hairline, colors.ink, PillShape)
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier = shape.height(SS.dimens.pill).padding(horizontal = PillPadding),
    ) {
        Text(label, style = SS.type.label, color = if (filled) colors.onNeedsYou else colors.ink, maxLines = 1)
    }
}

@Composable
private fun SavingPill(label: String) {
    val colors = SS.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .background(
                    colors.segmentTrack,
                    PillShape,
                ).height(SS.dimens.pill)
                .padding(horizontal = 12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(SpinnerSize),
            strokeWidth = 2.dp,
            color = colors.inkMuted,
            trackColor = Color.Transparent,
        )
        Text(stringResource(R.string.save), style = SS.type.label, color = colors.inkMuted, maxLines = 1)
    }
}

/**
 * What the formatting strip carries in landscape with the keyboard up,
 * when the header has stepped aside (UX-9): the name, the dot, the same
 * Save button as the header's Save-state table, and the header's status
 * line whenever it is something the user must not miss (a failed or
 * paused save, a read-only file).
 */
@Composable
fun stripDocumentFor(state: EditorUiState): StripDocument {
    val status = state.save.status
    val shown =
        when (status) {
            is SaveStatusLine.Failed,
            is SaveStatusLine.AutosavePaused,
            is SaveStatusLine.ReadOnly,
            is SaveStatusLine.UnsavedReadOnly,
            -> statusText(status)

            else -> null
        }
    return StripDocument(
        name = state.title.ifEmpty { stringResource(R.string.untitled) },
        isDirty = state.save.showDirtyDot,
        save = state.save.button.toStrip(),
        status = shown,
        statusIsError = status is SaveStatusLine.Failed || status is SaveStatusLine.AutosavePaused,
    )
}

/** The header's [SaveButton] as the strip draws it: one strip state per row of the table. */
fun SaveButton.toStrip(): StripSave =
    when (this) {
        SaveButton.AllSaved -> StripSave.Saved
        SaveButton.Save -> StripSave.Save
        SaveButton.Saving -> StripSave.Saving
        SaveButton.SaveAsDraft, SaveButton.SaveAsReadOnly -> StripSave.SaveAs
        SaveButton.SaveAsRequired -> StripSave.SaveAsRequired
        SaveButton.Retry -> StripSave.Retry
    }
