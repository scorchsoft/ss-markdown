package com.scorchsoft.ssmarkdown.editor.chrome

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.MenuItem

/** The ⋮ menu's groups, in the spec's order; a divider separates each group. */
private fun menuGroups(
    copyInMenu: Boolean,
    saveAll: Boolean,
): List<List<MenuItem>> =
    listOfNotNull(
        // Copy is a header action that moved in here at a large font: it comes first, not last (UX-29).
        if (copyInMenu) listOf(MenuItem.Copy) else null,
        // Save all appears only while it applies, like the ≡ panel's row (rubric #2).
        listOfNotNull(
            MenuItem.New,
            MenuItem.OpenFile,
            MenuItem.OpenFolder,
            MenuItem.SaveAs,
            MenuItem.SaveAll.takeIf { saveAll },
        ),
        listOf(MenuItem.FindReplace, MenuItem.VersionHistory),
        listOf(MenuItem.ExportPdf, MenuItem.ExportImages, MenuItem.Share, MenuItem.CopyRichText),
        listOf(MenuItem.Display, MenuItem.Settings),
    )

/**
 * ⋮: New · Open file · Open folder · Save as · (Save all (N)) │ Find &
 * replace · Version history │ Export PDF · Export images… · Share · Copy
 * as rich text │ Display · Settings. Above 1.5× font scale, Copy moves in
 * here too, at the top. Save all (N) shows only in folder mode while
 * [unsavedCount] files are unsaved; the ≡ panel keeps its own row.
 * Keyboard shortcuts ("Ctrl F") show only with a hardware keyboard
 * attached, as the Navigator's Ctrl K does.
 */
@Composable
fun OverflowMenu(
    expanded: Boolean,
    copyInMenu: Boolean,
    onChoose: (MenuItem) -> Unit,
    onDismiss: () -> Unit,
    unsavedCount: Int = 0,
) {
    val hardwareKeyboard = LocalConfiguration.current.keyboard == Configuration.KEYBOARD_QWERTY
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = SS.colors.chrome) {
        Column {
            menuGroups(copyInMenu, saveAll = unsavedCount > 0).forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
                group.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            val label =
                                if (item == MenuItem.SaveAll) {
                                    stringResource(R.string.menu_save_all, unsavedCount)
                                } else {
                                    stringResource(labelOf(item))
                                }
                            Text(label, style = SS.type.body, color = SS.colors.ink)
                        },
                        trailingIcon =
                            hintOf(item, hardwareKeyboard)?.let { hint ->
                                { Text(stringResource(hint), style = SS.type.meta, color = SS.colors.inkMuted) }
                            },
                        onClick = { onChoose(item) },
                    )
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod") // One branch per menu row.
private fun labelOf(item: MenuItem): Int =
    when (item) {
        MenuItem.New -> R.string.menu_new
        MenuItem.OpenFile -> R.string.menu_open_file
        MenuItem.OpenFolder -> R.string.menu_open_folder
        MenuItem.SaveAs -> R.string.menu_save_as
        MenuItem.SaveAll -> R.string.folder_save_all
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

private fun hintOf(
    item: MenuItem,
    hardwareKeyboard: Boolean,
): Int? =
    when (item) {
        MenuItem.FindReplace -> if (hardwareKeyboard) R.string.menu_find_hint else null
        MenuItem.ExportImages -> R.string.menu_export_images_hint
        MenuItem.Display -> R.string.menu_display_hint
        else -> null
    }
