package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SS

private val DotSize = 8.dp
private val NameMaxWidth = 200.dp
private val DividerHeight = 24.dp
private val PillShape = RoundedCornerShape(20.dp)

/**
 * The filename and unsaved dot that lead the strip in landscape. The
 * name is truncated with an ellipsis; the dot is red because unsaved is
 * the one state that needs you.
 */
@Composable
internal fun StripDocumentHeader(document: StripDocument) {
    val colors = SS.colors
    val unsaved = stringResource(StripLabels.unsaved)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 16.dp, end = 12.dp),
    ) {
        Text(
            document.name,
            style = SS.type.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = NameMaxWidth),
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
    Box(Modifier.width(SS.dimens.hairline).height(DividerHeight).background(colors.border))
}

/**
 * Save, as the header shows it: a red pill while there are unsaved
 * changes, and a muted tick once there are none (docs/ux-spec.md, "Clean
 * tick"), which is still the Save button so its place never moves.
 */
@Composable
internal fun StripSaveButton(
    isDirty: Boolean,
    onSave: () -> Unit,
) {
    val colors = SS.colors
    val label = stringResource(if (isDirty) StripLabels.save else StripLabels.saved)
    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier =
            Modifier
                .height(SS.dimens.pillHitArea)
                .padding(start = 4.dp)
                .clickable(role = Role.Button, onClick = onSave)
                .clearAndSetSemantics { contentDescription = label },
    ) {
        if (isDirty) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .height(SS.dimens.pill)
                        .background(colors.needsYou, PillShape)
                        .padding(horizontal = 16.dp),
            ) {
                Text(label, style = SS.type.label, color = colors.onNeedsYou)
            }
        } else {
            Box(Modifier.size(SS.dimens.iconButton), contentAlignment = Alignment.Center) {
                Icon(
                    StripIcons.saved,
                    contentDescription = null,
                    tint = colors.inkMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
