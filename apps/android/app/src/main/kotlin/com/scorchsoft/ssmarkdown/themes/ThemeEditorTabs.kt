package com.scorchsoft.ssmarkdown.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl
import com.scorchsoft.ssmarkdown.theme.PaletteKey
import com.scorchsoft.ssmarkdown.theme.ThemeColor
import com.scorchsoft.ssmarkdown.theme.ThemeVariant
import com.scorchsoft.ssmarkdown.ui.SectionLabel
import com.scorchsoft.ssmarkdown.ui.SettingRow
import java.util.Locale

/** Type: the three fonts, and Advanced CSS. */
@Composable
internal fun TypeTab(
    state: ThemeEditorUiState,
    onAction: (ThemeEditorAction) -> Unit,
) {
    val fonts = state.theme.fonts
    SettingRow(stringResource(R.string.theme_font_body), value = fonts.body, onClick = {
        onAction(ThemeEditorAction.PickFont(FontRole.Body))
    })
    SettingRow(
        stringResource(R.string.theme_font_heading),
        detail = if (fonts.heading == fonts.body) stringResource(R.string.theme_same_as_body) else null,
        value = fonts.heading,
        onClick = { onAction(ThemeEditorAction.PickFont(FontRole.Heading)) },
    )
    SettingRow(stringResource(R.string.theme_font_code), value = fonts.code, onClick = {
        onAction(ThemeEditorAction.PickFont(FontRole.Code))
    })
    SettingRow(
        stringResource(R.string.theme_css),
        detail = stringResource(R.string.theme_css_detail),
        value =
            if (state.theme.css.isBlank()) {
                stringResource(
                    R.string.theme_css_empty,
                )
            } else {
                stringResource(R.string.theme_css_set)
            },
        onClick = { onAction(ThemeEditorAction.ShowCss(!state.editingCss)) },
    )
    if (state.editingCss) {
        var css by rememberSaveable(state.theme.id) { mutableStateOf(state.theme.css) }
        OutlinedTextField(
            value = css,
            onValueChange = {
                css = it
                onAction(ThemeEditorAction.EditCss(it))
            },
            textStyle = SS.type.code.copy(color = SS.colors.ink),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(horizontal = 16.dp),
        )
    }
}

/** Colours: each palette key for the chosen variant, and the contrast warnings with Fix it. */
@Composable
internal fun ColoursTab(
    state: ThemeEditorUiState,
    onAction: (ThemeEditorAction) -> Unit,
) {
    SSSegmentedControl(
        options = listOf(stringResource(R.string.appearance_light), stringResource(R.string.appearance_dark)),
        selectedIndex = state.variant.ordinal,
        onSelect = { onAction(ThemeEditorAction.SelectVariant(ThemeVariant.entries[it])) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    val palette = state.theme.palette(state.variant)
    PaletteKey.entries.forEach { key -> ColourRow(key, palette[key].hex, state.variant, onAction) }
    if (state.contrast.isNotEmpty()) {
        SectionLabel(stringResource(R.string.theme_contrast))
        state.contrast.forEach { Problem(it.message()) }
        Box(Modifier.padding(horizontal = 16.dp)) {
            OutlinedButton(onClick = { onAction(ThemeEditorAction.FixContrast) }) {
                Text(stringResource(R.string.theme_fix_contrast), style = SS.type.label, color = SS.colors.ink)
            }
        }
    }
}

@Composable
private fun ColourRow(
    key: PaletteKey,
    hex: String,
    variant: ThemeVariant,
    onAction: (ThemeEditorAction) -> Unit,
) {
    var draft by rememberSaveable(key, variant, hex) { mutableStateOf(hex) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = SS.dimens.rowPrimary,
                ).padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        val swatch = ThemeColor.parse(hex)
        Box(
            Modifier
                .size(28.dp)
                .background(swatch?.toCompose() ?: SS.colors.bg, CircleShape)
                .border(SS.dimens.hairline, SS.colors.border, CircleShape),
        )
        Text(key.json, style = SS.type.body, color = SS.colors.ink, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                if (ThemeColor.parse(it) != null) onAction(ThemeEditorAction.SetColor(key, it))
            },
            singleLine = true,
            isError = ThemeColor.parse(draft) == null,
            textStyle = SS.type.code.copy(color = SS.colors.ink),
            modifier = Modifier.width(132.dp),
        )
    }
}

/** Spacing: base size, heading scale and line height, each a stepper. */
@Composable
internal fun SpacingTab(
    state: ThemeEditorUiState,
    onAction: (ThemeEditorAction) -> Unit,
) {
    val size = state.theme.size
    StepRow(stringResource(R.string.theme_base_size), stringResource(R.string.theme_px, size.base.toInt())) {
        onAction(ThemeEditorAction.StepBase(it))
    }
    StepRow(stringResource(R.string.theme_heading_scale), "%.2f".format(Locale.ROOT, size.headingScale)) {
        onAction(ThemeEditorAction.StepScale(it))
    }
    StepRow(stringResource(R.string.theme_line_height), "%.2f".format(Locale.ROOT, size.lineHeight)) {
        onAction(ThemeEditorAction.StepLineHeight(it))
    }
}

@Composable
private fun StepRow(
    label: String,
    value: String,
    onStep: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = SS.dimens.rowPrimary).padding(horizontal = 16.dp),
    ) {
        Text(label, style = SS.type.body, color = SS.colors.ink, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onStep(-1) }) { Text("−", style = SS.type.label, color = SS.colors.ink) }
        Text(value, style = SS.type.label, color = SS.colors.ink)
        OutlinedButton(onClick = { onStep(1) }) { Text("+", style = SS.type.label, color = SS.colors.ink) }
    }
}

/** JSON: the theme as a file, editable; it applies the moment it is a valid theme. */
@Composable
internal fun JsonTab(
    state: ThemeEditorUiState,
    onAction: (ThemeEditorAction) -> Unit,
) {
    OutlinedTextField(
        value = state.json,
        onValueChange = { onAction(ThemeEditorAction.EditJson(it)) },
        textStyle = SS.type.code.copy(color = SS.colors.ink),
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(horizontal = 16.dp),
    )
    TextButton(onClick = { onAction(ThemeEditorAction.Export) }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.theme_export), style = SS.type.label, color = SS.colors.ink)
    }
}
