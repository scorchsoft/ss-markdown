package com.scorchsoft.ssmarkdown.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SS

private val TrackPadding = 3.dp
private val TrackShape = RoundedCornerShape(12.dp)
private val SegmentShape = RoundedCornerShape(9.dp)
private val SegmentWidth = 104.dp

/**
 * A single-choice segmented control: Code / Split / Preview and friends.
 *
 * Each segment is a radio button to accessibility services, inside a
 * selectable group, so TalkBack reads "Split, 2 of 3, not selected". The
 * selected segment is lifted with a hairline shadow in light mode and by
 * tone alone in dark, where shadows do not read.
 */
@Composable
fun SSSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SS.colors
    Row(
        modifier =
            modifier
                .background(colors.segmentTrack, TrackShape)
                .padding(TrackPadding)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .height(SS.dimens.segment)
                        .width(SegmentWidth)
                        .then(if (selected && !colors.isDark) Modifier.shadow(1.dp, SegmentShape) else Modifier)
                        .background(if (selected) colors.segmentSelected else Color.Transparent, SegmentShape)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) }),
            ) {
                Text(
                    text = label,
                    style = SS.type.label,
                    color = if (selected) colors.ink else colors.inkMuted,
                )
            }
        }
    }
}
