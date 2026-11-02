package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.ui.AppIcons

private val IconSegmentWidth = 64.dp
private val TrackShape = RoundedCornerShape(12.dp)
private val SegmentShape = RoundedCornerShape(9.dp)

/**
 * The 64 dp bottom bar: Code | Split | Preview. Above 1.5× font scale the
 * switcher goes icon-only, keeping the labels for TalkBack (ux-spec
 * "Font scale above 1.5×"). [modes] drops Split and Preview for `.txt`.
 */
@Composable
fun ModeBar(
    mode: ViewMode,
    onSelect: (ViewMode) -> Unit,
    iconOnly: Boolean,
    modifier: Modifier = Modifier,
    modes: List<ViewMode> = ViewMode.entries,
) {
    Column(modifier.background(SS.colors.chrome)) {
        HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
        // A floor, not a height: the segments grow with the font (UX-7, UX-8).
        Box(Modifier.fillMaxWidth().heightIn(min = SS.dimens.bottomBar), contentAlignment = Alignment.Center) {
            ModeSwitcher(mode, onSelect, iconOnly, modes, withIcons = true)
        }
    }
}

/**
 * The segmented switcher alone, for the landscape bar. [withIcons] puts a
 * glyph before each label (the bottom bar); the 48 dp landscape bar keeps
 * labels only, where the room is tight.
 */
@Composable
fun ModeSwitcher(
    mode: ViewMode,
    onSelect: (ViewMode) -> Unit,
    iconOnly: Boolean,
    modes: List<ViewMode> = ViewMode.entries,
    withIcons: Boolean = false,
) {
    val labels = modes.map { modeLabel(it) }
    if (!iconOnly) {
        SSSegmentedControl(
            options = labels,
            selectedIndex = modes.indexOf(mode),
            onSelect = { onSelect(modes[it]) },
            icons = if (withIcons) modes.map(::modeIcon) else null,
        )
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.background(SS.colors.segmentTrack, TrackShape).padding(3.dp).selectableGroup(),
    ) {
        modes.forEachIndexed { index, item ->
            val selected = item == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .width(IconSegmentWidth)
                        .height(SS.dimens.segment)
                        .background(if (selected) SS.colors.segmentSelected else SS.colors.segmentTrack, SegmentShape)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(item) })
                        .semantics { contentDescription = labels[index] },
            ) {
                Icon(
                    modeIcon(item),
                    contentDescription = null,
                    tint = if (selected) SS.colors.ink else SS.colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun modeLabel(mode: ViewMode): String =
    stringResource(
        when (mode) {
            ViewMode.Code -> R.string.mode_code
            ViewMode.Split -> R.string.mode_split
            ViewMode.Preview -> R.string.mode_preview
        },
    )

private fun modeIcon(mode: ViewMode) =
    when (mode) {
        ViewMode.Code -> AppIcons.code
        ViewMode.Split -> AppIcons.split
        ViewMode.Preview -> AppIcons.preview
    }
