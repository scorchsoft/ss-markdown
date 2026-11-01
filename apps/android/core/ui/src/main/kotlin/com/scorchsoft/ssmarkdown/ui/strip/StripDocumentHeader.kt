package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SS

private val DotSize = 8.dp
private val NameMaxWidth = 200.dp
private val DividerHeight = 24.dp
private val PillShape = RoundedCornerShape(20.dp)
private val SpinnerSize = 16.dp

/**
 * The filename and unsaved dot that lead the strip in landscape, with the
 * header's status line under them when it has something to say. The name
 * is truncated with an ellipsis; the dot is red because unsaved is the one
 * state that needs you.
 */
@Composable
internal fun StripDocumentHeader(document: StripDocument) {
    val colors = SS.colors
    val unsaved = stringResource(StripLabels.unsaved)
    Column(Modifier.padding(start = 16.dp, end = 12.dp).widthIn(max = NameMaxWidth)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                document.name,
                style = SS.type.label,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (document.isDirty) {
                Box(
                    Modifier
                        .size(DotSize)
                        .background(colors.needsYou, CircleShape)
                        .semantics { contentDescription = unsaved },
                )
            }
        }
        document.status?.let { status ->
            Text(
                status,
                style = SS.type.caption,
                color = if (document.statusIsError) colors.needsYouText else colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Box(Modifier.width(SS.dimens.hairline).height(DividerHeight).background(colors.border))
}

/**
 * Save, as the header shows it ([StripSave]): a red pill when the save
 * needs you, an outlined "Save as…" for a draft or a read-only file, a
 * spinner while saving, and a muted tick once all is saved (docs/ux-spec.md,
 * "Clean tick"), which is still the Save button so its place never moves.
 */
@Composable
internal fun StripSaveButton(
    save: StripSave,
    onSave: () -> Unit,
) {
    val colors = SS.colors
    val label = stringResource(save.label)
    val saving = save == StripSave.Saving
    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier =
            Modifier
                .height(SS.dimens.pillHitArea)
                .padding(start = 4.dp)
                .clickable(enabled = !saving, role = Role.Button, onClick = onSave)
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    if (saving) disabled()
                },
    ) {
        when (save) {
            StripSave.Saved -> {
                Box(Modifier.size(SS.dimens.iconButton), contentAlignment = Alignment.Center) {
                    Icon(
                        StripIcons.saved,
                        contentDescription = null,
                        tint = colors.inkMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            StripSave.Saving -> {
                SavingFace(label)
            }

            else -> {
                val face =
                    if (save.filled) {
                        Modifier.background(colors.needsYou, PillShape)
                    } else {
                        Modifier.border(SS.dimens.hairline, colors.ink, PillShape)
                    }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = face.height(SS.dimens.pill).padding(horizontal = 16.dp),
                ) {
                    Text(label, style = SS.type.label, color = if (save.filled) colors.onNeedsYou else colors.ink)
                }
            }
        }
    }
}

@Composable
private fun SavingFace(label: String) {
    val colors = SS.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .height(SS.dimens.pill)
                .background(colors.segmentTrack, PillShape)
                .padding(horizontal = 12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(SpinnerSize),
            strokeWidth = 2.dp,
            color = colors.inkMuted,
            trackColor = Color.Transparent,
        )
        Text(label, style = SS.type.label, color = colors.inkMuted, maxLines = 1)
    }
}
