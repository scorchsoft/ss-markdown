package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.MenuItem
import com.scorchsoft.ssmarkdown.settings.CopyFormat
import com.scorchsoft.ssmarkdown.ui.AppIcons

/** Long-press Save: Save as… · Autosave this file (on/off). */
@Composable
fun SaveMenu(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    val overlay = state.overlay as? EditorOverlay.SaveMenu
    DropdownMenu(expanded = overlay != null, onDismissRequest = {
        onAction(EditorAction.DismissOverlay)
    }, containerColor = SS.colors.chrome) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.save_as), style = SS.type.body, color = SS.colors.ink) },
            onClick = { onAction(EditorAction.ChooseMenuItem(MenuItem.SaveAs)) },
        )
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.save_menu_autosave_file),
                    style = SS.type.body,
                    color = SS.colors.ink,
                )
            },
            trailingIcon = {
                Switch(
                    checked = overlay?.autosaveThisFile == true,
                    onCheckedChange = null,
                    colors = inkSwitchColors(),
                )
            },
            onClick = { onAction(EditorAction.ToggleAutosaveThisFile) },
        )
    }
}

/**
 * The Copy chooser (ux-spec "Top bar"), anchored to Copy (or to ⋮ when Copy
 * has moved in there): Copy Markdown, the source exactly, and Copy formatted
 * text, for Gmail, Google Docs, Word…. The last-used choice is first, so a
 * copy stays two taps in the same place. Each row is a two-line, 56 dp item
 * that TalkBack reads as its title and what it is for.
 */
@Composable
fun CopyChooser(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    val chooser = state.overlay as? EditorOverlay.CopyChooser
    DropdownMenu(
        expanded = chooser != null,
        onDismissRequest = { onAction(EditorAction.DismissOverlay) },
        containerColor = SS.colors.chrome,
    ) {
        copyOrder(chooser?.first ?: state.copyFormat).forEach { format -> CopyRow(format, onAction) }
    }
}

/** The two copy choices, [first] on top. */
fun copyOrder(first: CopyFormat): List<CopyFormat> = listOf(first) + CopyFormat.entries.filter { it != first }

@Composable
private fun CopyRow(
    format: CopyFormat,
    onAction: (EditorAction) -> Unit,
) {
    val (title, hint, icon) =
        when (format) {
            CopyFormat.Markdown -> Triple(R.string.copy_markdown, R.string.copy_markdown_hint, AppIcons.code)
            CopyFormat.Formatted -> Triple(R.string.copy_formatted, R.string.copy_formatted_hint, AppIcons.preview)
        }
    DropdownMenuItem(
        text = {
            Column {
                Text(stringResource(title), style = SS.type.body, color = SS.colors.ink)
                Text(stringResource(hint), style = SS.type.meta, color = SS.colors.inkMuted)
            }
        },
        leadingIcon = { Icon(icon, contentDescription = null, tint = SS.colors.ink) },
        onClick = { onAction(EditorAction.CopyAs(format)) },
        modifier = Modifier.heightIn(min = CopyRowHeight).testTag("copy-${format.name.lowercase()}"),
    )
}

private val CopyRowHeight = 56.dp

/** Switches are ink, never red (ux-spec "Colour rule"). */
@Composable
fun inkSwitchColors() =
    SwitchDefaults.colors(
        checkedThumbColor = SS.colors.bg,
        checkedTrackColor = SS.colors.ink,
        uncheckedThumbColor = SS.colors.inkMuted,
        uncheckedTrackColor = SS.colors.segmentTrack,
        uncheckedBorderColor = SS.colors.border,
    )
