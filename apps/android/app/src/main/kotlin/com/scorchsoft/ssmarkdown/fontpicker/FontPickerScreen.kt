package com.scorchsoft.ssmarkdown.fontpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.fonts.FontCategory
import com.scorchsoft.ssmarkdown.themes.FontRole
import com.scorchsoft.ssmarkdown.themes.Notice
import com.scorchsoft.ssmarkdown.ui.NoteText
import com.scorchsoft.ssmarkdown.ui.SecondaryScreen
import com.scorchsoft.ssmarkdown.ui.SectionLabel

private val Shape = RoundedCornerShape(12.dp)
private val ProgressWidth = 72.dp

/**
 * Choose a font (mockup FontPicker): search, category chips, what is on
 * this phone, then Google Fonts with Get, progress and Cancel, and the
 * one sentence that explains the only download the app ever makes.
 */
@Composable
fun FontPickerScreen(
    state: FontPickerUiState,
    onAction: (FontPickerAction) -> Unit,
) {
    SecondaryScreen(title = stringResource(R.string.font_title), onBack = { onAction(FontPickerAction.Back) }) {
        Text(
            stringResource(R.string.font_for, stringResource(roleLabel(state.role)), state.themeName),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(FontPickerAction.Search(it)) },
            placeholder = { Text(stringResource(R.string.font_search), color = SS.colors.inkMuted) },
            singleLine = true,
            textStyle = SS.type.body.copy(color = SS.colors.ink),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Chips(state.category, onAction)
        state.problem?.let { Notice(it, R.string.font_ok) { onAction(FontPickerAction.DismissProblem) } }
        SectionLabel(stringResource(R.string.font_on_phone))
        state.onPhone.forEach { PhoneRow(it, it.family == state.current, onAction) }
        SectionLabel(stringResource(R.string.font_google))
        state.google.forEach { GoogleRow(it, state.downloading[it.family], onAction) }
        NoteText(stringResource(R.string.font_download_note))
        TextButton(onClick = { onAction(FontPickerAction.Import) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.font_import), style = SS.type.label, color = SS.colors.ink)
        }
    }
}

private fun roleLabel(role: FontRole): Int =
    when (role) {
        FontRole.Body -> R.string.theme_font_body
        FontRole.Heading -> R.string.theme_font_heading
        FontRole.Code -> R.string.theme_font_code
    }

@Composable
private fun Chips(
    selected: FontCategory?,
    onAction: (FontPickerAction) -> Unit,
) {
    val options =
        listOf(
            null to R.string.font_all,
            FontCategory.SERIF to R.string.font_serif,
            FontCategory.SANS to R.string.font_sans,
            FontCategory.MONO to R.string.font_mono,
        )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        options.forEach { (category, label) ->
            FilterChip(
                selected = selected == category,
                onClick = { onAction(FontPickerAction.Filter(category)) },
                label = { Text(stringResource(label), style = SS.type.label) },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SS.colors.ink,
                        selectedLabelColor = SS.colors.bg,
                        labelColor = SS.colors.ink,
                    ),
            )
        }
    }
}

@Composable
private fun PhoneRow(
    option: FontOption,
    current: Boolean,
    onAction: (FontPickerAction) -> Unit,
) {
    val origin =
        when (option.origin) {
            FontOrigin.BuiltIn -> R.string.font_built_in
            FontOrigin.Downloaded -> R.string.font_downloaded
            FontOrigin.Imported -> R.string.font_imported
            FontOrigin.GoogleFonts -> R.string.font_google
        }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SS.dimens.rowPrimary)
            .padding(horizontal = 8.dp)
            .background(if (current) SS.colors.segmentTrack else SS.colors.bg, Shape)
            .clickable(role = Role.Button) { onAction(FontPickerAction.Choose(option.family)) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(option.family, style = SS.type.bodyStrong, color = SS.colors.ink)
        Text(
            stringResource(origin) + if (current) " · " + stringResource(R.string.font_current) else "",
            style = SS.type.meta,
            color = SS.colors.inkMuted,
        )
    }
}

@Composable
private fun GoogleRow(
    option: FontOption,
    progress: Float?,
    onAction: (FontPickerAction) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = SS.dimens.rowPrimary,
                ).padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.family, style = SS.type.bodyStrong, color = SS.colors.ink)
            Text(
                pluralStringResource(
                    R.plurals.font_google_detail,
                    option.styles ?: 0,
                    option.category?.let { stringResource(categoryLabel(it)) }.orEmpty(),
                    option.styles ?: 0,
                    option.approxKb ?: 0,
                ),
                style = SS.type.meta,
                color = SS.colors.inkMuted,
            )
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                color = SS.colors.ink,
                // The neutral track, never Material's stock container colour (UX2-9).
                trackColor = SS.colors.segmentTrack,
                modifier = Modifier.width(ProgressWidth),
            )
            TextButton(onClick = { onAction(FontPickerAction.Cancel(option.family)) }) {
                Text(stringResource(R.string.cancel), style = SS.type.label, color = SS.colors.ink)
            }
        } else {
            TextButton(onClick = { onAction(FontPickerAction.Download(option.family)) }) {
                Text(stringResource(R.string.font_get), style = SS.type.label, color = SS.colors.ink)
            }
        }
    }
}

private fun categoryLabel(category: FontCategory): Int =
    when (category) {
        FontCategory.SERIF -> R.string.font_serif
        FontCategory.SANS -> R.string.font_sans
        FontCategory.MONO -> R.string.font_mono
        FontCategory.DISPLAY -> R.string.font_display
        FontCategory.HANDWRITING -> R.string.font_handwriting
    }
