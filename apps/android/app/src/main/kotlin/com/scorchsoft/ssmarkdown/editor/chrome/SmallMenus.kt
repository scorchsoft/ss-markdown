package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.MenuItem

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

/** Long-press Copy: Copy as rich text. */
@Composable
fun CopyMenu(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    DropdownMenu(
        expanded = state.overlay == EditorOverlay.CopyMenu,
        onDismissRequest = { onAction(EditorAction.DismissOverlay) },
        containerColor = SS.colors.chrome,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_copy_rich), style = SS.type.body, color = SS.colors.ink) },
            onClick = { onAction(EditorAction.ChooseMenuItem(MenuItem.CopyRichText)) },
        )
    }
}

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
