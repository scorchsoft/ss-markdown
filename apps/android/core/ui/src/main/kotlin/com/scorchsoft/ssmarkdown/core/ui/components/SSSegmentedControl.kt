package com.scorchsoft.ssmarkdown.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SS

private val TrackPadding = 3.dp
private val SegmentGap = 2.dp
private val TrackShape = RoundedCornerShape(12.dp)
private val SegmentShape = RoundedCornerShape(9.dp)
private val LabelPadding = 12.dp
private val IconSize = 18.dp
private val IconGap = 6.dp

/** The design canvas's segment width: what each segment gets when there is room. */
private val PreferredSegmentWidth = 104.dp

/**
 * A single-choice segmented control: Code / Split / Preview and friends.
 *
 * Each segment is a radio button to accessibility services, inside a
 * selectable group, so TalkBack reads "Split, 2 of 3, not selected". The
 * selected segment is lifted with a hairline shadow in light mode and by
 * tone alone in dark, where shadows do not read.
 *
 * Sizing (UX-8) follows the labels and the room, never a fixed width:
 * - a segment is [PreferredSegmentWidth] wide, or wider when its label
 *   needs it, and segments share the room equally when there is less;
 * - given a width to fill (`Modifier.fillMaxWidth()`), the segments share
 *   all of it, so the track is never half empty;
 * - segments grow taller with the font (44 dp is a floor);
 * - when a label cannot fit its share on one line, at a large font or in
 *   a narrow pane, the control becomes a vertical list of radio rows,
 *   which always fits and reads the same to TalkBack.
 *
 * [icons], when given, puts a small glyph before each segment's label (one
 * per option); it is decoration, so TalkBack still reads only the label.
 */
@Composable
fun SSSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector>? = null,
) {
    val style = SS.type.label
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val widest =
            with(density) {
                options.maxOfOrNull {
                    measurer
                        .measure(it, style, maxLines = 1)
                        .size.width
                        .toDp()
                } ?: 0.dp
            } + LabelPadding * 2 + (if (icons != null) IconSize + IconGap else 0.dp)
        val sizing = SegmentSizing.of(options.size, maxWidth, widest, fill = constraints.hasFixedWidth)
        if (sizing.stacked) {
            StackedChoices(options, selectedIndex, onSelect)
        } else {
            Segments(options, selectedIndex, onSelect, sizing.segmentWidth, icons)
        }
    }
}

@Composable
private fun Segments(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    segmentWidth: Dp,
    icons: List<ImageVector>?,
) {
    val colors = SS.colors
    Row(
        modifier =
            Modifier
                .background(colors.segmentTrack, TrackShape)
                .padding(TrackPadding)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SegmentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .heightIn(min = SS.dimens.segment)
                        .width(segmentWidth)
                        .then(if (selected && !colors.isDark) Modifier.shadow(1.dp, SegmentShape) else Modifier)
                        .background(if (selected) colors.segmentSelected else Color.Transparent, SegmentShape)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) })
                        .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                val ink = if (selected) colors.ink else colors.inkMuted
                Row(verticalAlignment = Alignment.CenterVertically) {
                    icons?.getOrNull(index)?.let {
                        Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(IconSize))
                        Spacer(Modifier.width(IconGap))
                    }
                    Text(
                        text = label,
                        style = SS.type.label,
                        color = ink,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** The large-font fallback: one radio row per option, in a tinted box like the track. */
@Composable
private fun StackedChoices(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = SS.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.segmentTrack, TrackShape)
            .padding(TrackPadding)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = SS.dimens.rowPrimary)
                        .background(if (selected) colors.segmentSelected else Color.Transparent, SegmentShape)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) })
                        .padding(horizontal = 8.dp),
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = colors.ink, unselectedColor = colors.inkMuted),
                )
                Text(
                    label,
                    style = SS.type.label,
                    color = colors.ink,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * How wide each segment is, or whether the control stacks: pure, so the
 * rules are tested without a UI.
 */
internal data class SegmentSizing(
    val segmentWidth: Dp,
    val stacked: Boolean,
) {
    companion object {
        /**
         * [count] segments in [available] width (unbounded is [Dp.Infinity]),
         * whose widest label needs [widestLabel]. [fill] means the caller
         * asked for the whole width.
         */
        fun of(
            count: Int,
            available: Dp,
            widestLabel: Dp,
            fill: Boolean,
        ): SegmentSizing {
            val preferred = maxOf(PreferredSegmentWidth, widestLabel)
            if (count == 0 || available == Dp.Infinity) return SegmentSizing(preferred, stacked = false)
            val share = (available - TrackPadding * 2 - SegmentGap * (count - 1)) / count
            return when {
                widestLabel > share -> SegmentSizing(share, stacked = true)
                fill -> SegmentSizing(share, stacked = false)
                else -> SegmentSizing(minOf(preferred, share), stacked = false)
            }
        }
    }
}
