package com.scorchsoft.ssmarkdown.editor.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.settings.Appearance

/**
 * ⋮ → Display (ux-spec "Display sheet"): Appearance, this file's text
 * size for each pane, and Code wrapping. The preview theme, fonts and
 * Preview's page modes come from `core/theme` through [themeControls].
 */
@Composable
fun DisplaySheet(
    state: EditorUiState,
    appearance: Appearance,
    onAction: (EditorAction) -> Unit,
    themeControls: @Composable () -> Unit,
) {
    SSSheet(onDismiss = { onAction(EditorAction.DismissOverlay) }) {
        SheetTitle(stringResource(R.string.display_title))
        Label(R.string.display_appearance)
        SSSegmentedControl(
            options =
                listOf(
                    stringResource(R.string.appearance_system),
                    stringResource(R.string.appearance_light),
                    stringResource(R.string.appearance_dark),
                ),
            selectedIndex = appearance.ordinal,
            onSelect = { onAction(EditorAction.SetAppearance(Appearance.entries[it])) },
        )
        Divider()
        themeControls()
        Divider()
        Label(R.string.display_text_size)
        Stepper(stringResource(R.string.display_code_size), state.codeZoom, Pane.Code, onAction)
        Stepper(stringResource(R.string.display_preview_size), state.previewZoom, Pane.Preview, onAction)
        Divider()
        Label(R.string.display_wrap)
        SSSegmentedControl(
            options = listOf(stringResource(R.string.wrap_on), stringResource(R.string.wrap_off)),
            selectedIndex = if (state.codeWrap) 0 else 1,
            onSelect = { onAction(EditorAction.SetCodeWrap(it == 0)) },
        )
    }
}

/** The default [DisplaySheet] theme slot until `core/theme` arrives. */
@Composable
fun ThemeControlsPlaceholder() {
    SheetBody(stringResource(R.string.display_theme_placeholder), muted = true)
}

@Composable
private fun Label(text: Int) {
    Text(
        stringResource(text),
        style = SS.type.label,
        color = SS.colors.inkMuted,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}

@Composable
private fun Divider() =
    HorizontalDivider(
        thickness = SS.dimens.hairline,
        color = SS.colors.border,
        modifier = Modifier.padding(vertical = 12.dp),
    )

@Composable
private fun Stepper(
    label: String,
    percent: Int,
    pane: Pane,
    onAction: (EditorAction) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = SS.type.body, color = SS.colors.ink, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            onAction(EditorAction.StepZoom(pane, -1))
        }) { Text("−", style = SS.type.label, color = SS.colors.ink) }
        Text(stringResource(R.string.zoom_chip, percent), style = SS.type.label, color = SS.colors.ink)
        OutlinedButton(onClick = {
            onAction(EditorAction.StepZoom(pane, 1))
        }) { Text("+", style = SS.type.label, color = SS.colors.ink) }
        TextButton(onClick = { onAction(EditorAction.ResetZoom(pane)) }) {
            Text(stringResource(R.string.display_reset), style = SS.type.label, color = SS.colors.ink)
        }
    }
}
