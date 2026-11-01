package com.scorchsoft.ssmarkdown.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl
import com.scorchsoft.ssmarkdown.ui.AppIcons
import com.scorchsoft.ssmarkdown.ui.NoteText
import com.scorchsoft.ssmarkdown.ui.SecondaryScreen
import com.scorchsoft.ssmarkdown.ui.SectionLabel
import com.scorchsoft.ssmarkdown.ui.SettingRow
import com.scorchsoft.ssmarkdown.ui.SwitchRow
import java.util.Locale

/** Code's 100 % in sp and Preview's in px (ux-spec "Text size: one model"). */
private const val CODE_BASE_SP = 12.5f
private const val PREVIEW_BASE_PX = 15
private const val PERCENT = 100

/**
 * Settings (ux-spec "Settings"; the Settings board). Pure: [settings] and
 * [storageSummary] in, [onAction] out. Fonts and custom themes are rows
 * that `core/theme` fills in; until then they say where the choice lives.
 *
 * @param storageSummary "48.2 MB used", or null before the first measurement.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    storageSummary: String?,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryScreen(stringResource(R.string.settings_title), { onAction(SettingsAction.Back) }, modifier) {
        Opening(settings, onAction)
        TextSection(settings, onAction)
        SectionLabel(stringResource(R.string.settings_saving))
        SettingRow(stringResource(R.string.settings_write))
        Segments(
            listOf(R.string.save_mode_tap, R.string.save_mode_auto),
            settings.saveMode.ordinal,
        ) { onAction(SettingsAction.SetSaveMode(SaveMode.entries[it])) }
        NoteText(stringResource(R.string.settings_saving_note))
        SettingRow(
            title = stringResource(R.string.settings_storage),
            detail =
                storageSummary?.let { stringResource(R.string.settings_storage_summary, it) }
                    ?: stringResource(R.string.settings_storage_summary_unknown),
            value =
                stringResource(
                    if (settings.history.recording) R.string.settings_history_on else R.string.settings_history_off,
                ),
            onClick = { onAction(SettingsAction.OpenStorage) },
        )
        SectionLabel(stringResource(R.string.settings_preview_split))
        SwitchRow(
            stringResource(R.string.settings_tap_to_edit),
            settings.tapToEditPreview,
            { onAction(SettingsAction.ToggleTapToEdit) },
            stringResource(R.string.settings_tap_to_edit_hint),
        )
        SwitchRow(stringResource(R.string.settings_scroll_together), settings.scrollPanesTogether, {
            onAction(SettingsAction.ToggleScrollTogether)
        })
        SectionLabel(stringResource(R.string.settings_themes))
        SettingRow(stringResource(R.string.settings_custom_themes), stringResource(R.string.settings_font_pending))
        SettingRow(
            stringResource(R.string.settings_about),
            value = "",
            onClick = { onAction(SettingsAction.OpenAbout) },
        )
    }
}

@Composable
private fun Opening(
    settings: AppSettings,
    onAction: (SettingsAction) -> Unit,
) {
    SectionLabel(stringResource(R.string.settings_opening))
    SettingRow(stringResource(R.string.settings_open_with))
    Segments(
        listOf(R.string.open_with_code, R.string.open_with_preview, R.string.open_with_last),
        settings.openWith.ordinal,
    ) { onAction(SettingsAction.SetOpenWith(OpenWith.entries[it])) }
    NoteText(stringResource(R.string.settings_open_with_note))
    SettingRow(stringResource(R.string.settings_on_launch))
    Segments(listOf(R.string.on_launch_reopen, R.string.on_launch_new), settings.onLaunch.ordinal) {
        onAction(SettingsAction.SetOnLaunch(OnLaunch.entries[it]))
    }
}

@Composable
private fun TextSection(
    settings: AppSettings,
    onAction: (SettingsAction) -> Unit,
) {
    SectionLabel(stringResource(R.string.settings_text))
    val codeSp = String.format(Locale.ROOT, "%.1f", CODE_BASE_SP * settings.defaultZoomCode / PERCENT)
    ZoomRow(
        stringResource(R.string.settings_zoom_code),
        stringResource(R.string.settings_zoom_code_value, settings.defaultZoomCode, codeSp),
    ) {
        onAction(SettingsAction.StepCodeZoom(it))
    }
    val previewPx = PREVIEW_BASE_PX * settings.defaultZoomPreview / PERCENT
    ZoomRow(
        stringResource(R.string.settings_zoom_preview),
        stringResource(R.string.settings_zoom_preview_value, settings.defaultZoomPreview, previewPx),
    ) {
        onAction(SettingsAction.StepPreviewZoom(it))
    }
    SettingRow(stringResource(R.string.settings_code_font), stringResource(R.string.settings_font_pending))
    SettingRow(stringResource(R.string.settings_preview_font), stringResource(R.string.settings_font_pending))
    SwitchRow(stringResource(R.string.settings_line_numbers), settings.lineNumbers, {
        onAction(SettingsAction.ToggleLineNumbers)
    })
}

@Composable
private fun ZoomRow(
    title: String,
    value: String,
    onStep: (Int) -> Unit,
) {
    SettingRow(title, value) {
        IconButton(onClick = {
            onStep(-1)
        }) { Icon(AppIcons.chevronDown, stringResource(R.string.decrease), tint = SS.colors.ink) }
        IconButton(onClick = {
            onStep(1)
        }) { Icon(AppIcons.chevronUp, stringResource(R.string.increase), tint = SS.colors.ink) }
    }
}

@Composable
private fun Segments(
    labels: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    SSSegmentedControl(
        options = labels.map { stringResource(it) },
        selectedIndex = selected,
        onSelect = onSelect,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
