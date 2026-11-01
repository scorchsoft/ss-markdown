package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.MenuItem

/** The ⋮ menu's groups, in the spec's order; a divider separates each group. */
private fun menuGroups(copyInMenu: Boolean): List<List<MenuItem>> =
    listOfNotNull(
        listOf(MenuItem.New, MenuItem.OpenFile, MenuItem.OpenFolder, MenuItem.SaveAs),
        listOf(MenuItem.FindReplace, MenuItem.VersionHistory),
        listOf(MenuItem.ExportPdf, MenuItem.ExportImages, MenuItem.Share, MenuItem.CopyRichText),
        listOf(MenuItem.Display, MenuItem.Settings),
        if (copyInMenu) listOf(MenuItem.Copy) else null,
    )

/**
 * ⋮: New · Open file · Open folder · Save as │ Find & replace · Version
 * history │ Export PDF · Export images… · Share · Copy as rich text │
 * Display · Settings. Above 1.5× font scale, Copy moves in here too.
 */
@Composable
fun OverflowMenu(
    expanded: Boolean,
    copyInMenu: Boolean,
    onChoose: (MenuItem) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = SS.colors.chrome) {
        Column {
            menuGroups(copyInMenu).forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
                group.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelOf(item)), style = SS.type.body, color = SS.colors.ink) },
                        trailingIcon =
                            hintOf(item)?.let { hint ->
                                { Text(stringResource(hint), style = SS.type.meta, color = SS.colors.inkMuted) }
                            },
                        onClick = { onChoose(item) },
                    )
                }
            }
        }
    }
}

private fun labelOf(item: MenuItem): Int =
    when (item) {
        MenuItem.New -> R.string.menu_new
        MenuItem.OpenFile -> R.string.menu_open_file
        MenuItem.OpenFolder -> R.string.menu_open_folder
        MenuItem.SaveAs -> R.string.menu_save_as
        MenuItem.FindReplace -> R.string.menu_find_replace
        MenuItem.VersionHistory -> R.string.menu_version_history
        MenuItem.ExportPdf -> R.string.menu_export_pdf
        MenuItem.ExportImages -> R.string.menu_export_images
        MenuItem.Share -> R.string.menu_share
        MenuItem.CopyRichText -> R.string.menu_copy_rich
        MenuItem.Display -> R.string.menu_display
        MenuItem.Settings -> R.string.menu_settings
        MenuItem.Copy -> R.string.menu_copy
    }

private fun hintOf(item: MenuItem): Int? =
    when (item) {
        MenuItem.FindReplace -> R.string.menu_find_hint
        MenuItem.ExportImages -> R.string.menu_export_images_hint
        MenuItem.Display -> R.string.menu_display_hint
        else -> null
    }
