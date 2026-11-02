package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.ui.AppIcons

private val ChipShape = RoundedCornerShape(20.dp)

/**
 * "120% ↺" in the top-right corner of the pane it belongs to, only when
 * that pane's zoom differs from the default. Tapping resets it.
 */
@Composable
fun ZoomChip(
    percent: Int,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.zoom_chip_reset, percent)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .height(SS.dimens.chip)
                .background(SS.colors.chrome, ChipShape)
                .border(SS.dimens.hairline, SS.colors.border, ChipShape)
                .clickable(role = Role.Button, onClick = onReset)
                .semantics { contentDescription = label }
                .padding(horizontal = 12.dp),
    ) {
        Text(stringResource(R.string.zoom_chip, percent), style = SS.type.label, color = SS.colors.ink)
        Icon(AppIcons.reset, contentDescription = null, tint = SS.colors.inkMuted, modifier = Modifier.size(16.dp))
    }
}

/** "Pinching · 112%": shown only while fingers are down. */
@Composable
fun PinchPill(
    percent: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        stringResource(R.string.zoom_pinching, percent),
        style = SS.type.label,
        color = SS.colors.bg,
        modifier = modifier.background(SS.colors.ink, ChipShape).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
