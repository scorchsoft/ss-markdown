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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.settings.Appearance
import com.scorchsoft.ssmarkdown.settings.PreviewWrap
import com.scorchsoft.ssmarkdown.ui.StepperButtons
import java.util.Locale

/** Code's 100 % is 12.5 sp (ux-spec "Text size: one model"). */
private const val CODE_BASE_SP = 12.5f
private const val PERCENT = 100f

/**
 * The display facts the sheet shows that are not in the editor's state.
 *
 * @property previewBasePx the preview theme's base size: what Preview's 100 % means.
 * @property previewWrap Preview wrapping: Fluid, the A4 page or No wrap.
 */
data class DisplayFacts(
    val appearance: Appearance,
    val previewBasePx: Double = 15.0,
    val previewWrap: PreviewWrap = PreviewWrap.Fluid,
)

/**
 * ⋮ → Display (ux-spec "Display sheet"; mockup Display): Appearance, the
 * preview theme ([themeControls]), this file's text size for each pane
 * with the real size beside it and a reset to your default, and line
 * wrapping for Code (Wrap / No wrap) and Preview (Fluid / A4 page / No wrap).
 */
@Composable
fun DisplaySheet(
    state: EditorUiState,
    facts: DisplayFacts,
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
            selectedIndex = facts.appearance.ordinal,
            onSelect = { onAction(EditorAction.SetAppearance(Appearance.entries[it])) },
        )
        Divider()
        themeControls()
        Divider()
        Label(R.string.display_text_size_file)
        val codeSize = sizeLabel(CODE_BASE_SP * state.codeZoom / PERCENT)
        val previewSize = sizeLabel((facts.previewBasePx * state.previewZoom / PERCENT).toFloat())
        Stepper(stringResource(R.string.display_code_size_sp, codeSize), state.codeZoom, Pane.Code, onAction)
        Stepper(
            stringResource(R.string.display_preview_size_px, previewSize),
            state.previewZoom,
            Pane.Preview,
            onAction,
        )
        TextButton(onClick = {
            onAction(EditorAction.ResetZoom(Pane.Code))
            onAction(EditorAction.ResetZoom(Pane.Preview))
        }) { Text(stringResource(R.string.display_reset_default), style = SS.type.label, color = SS.colors.ink) }
        Divider()
        Label(R.string.display_wrap)
        WrapRow(stringResource(R.string.mode_code)) {
            SSSegmentedControl(
                options = listOf(stringResource(R.string.wrap_on), stringResource(R.string.wrap_off)),
                selectedIndex = if (state.codeWrap) 0 else 1,
                onSelect = { onAction(EditorAction.SetCodeWrap(it == 0)) },
            )
        }
        WrapRow(stringResource(R.string.mode_preview)) {
            SSSegmentedControl(
                options =
                    listOf(
                        stringResource(R.string.wrap_fluid),
                        stringResource(R.string.wrap_a4),
                        stringResource(R.string.wrap_off),
                    ),
                selectedIndex = facts.previewWrap.ordinal,
                onSelect = { onAction(EditorAction.SetPreviewWrap(PreviewWrap.entries[it])) },
            )
        }
    }
}

private fun sizeLabel(value: Float): String = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")

@Composable
private fun WrapRow(
    label: String,
    control: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, style = SS.type.body, color = SS.colors.ink, modifier = Modifier.weight(1f))
        Row(Modifier.weight(2f)) { control() }
    }
}

/** The default [DisplaySheet] theme slot, for tests that run without the theme library. */
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
    val percentText = stringResource(R.string.zoom_chip, percent)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = SS.type.body, color = SS.colors.ink, modifier = Modifier.weight(1f))
        StepperButtons("$label −", "$label +", { onAction(EditorAction.StepZoom(pane, it)) }, percentText)
    }
}
