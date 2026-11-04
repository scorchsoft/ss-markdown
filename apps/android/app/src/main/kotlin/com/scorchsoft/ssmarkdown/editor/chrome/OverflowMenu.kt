package com.scorchsoft.ssmarkdown.editor.chrome

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.MenuItem
import com.scorchsoft.ssmarkdown.ui.AppIcons

/** The ⋮ menu's groups, in the spec's order; a divider separates each group. */
private fun menuGroups(
    copyInMenu: Boolean,
    saveAll: Boolean,
    showPictures: Boolean,
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
        // Show pictures appears only while the page has pictures it needs the folder for.
        listOfNotNull(
            MenuItem.FindReplace,
            MenuItem.InsertImage,
            MenuItem.ShowPictures.takeIf { showPictures },
            MenuItem.VersionHistory,
        ),
        // With Copy in here already (it opens the same chooser), Copy as… would be a twin row.
        listOfNotNull(
            MenuItem.ExportPdf,
            MenuItem.ExportImages,
            MenuItem.Share,
            MenuItem.CopyAs.takeUnless { copyInMenu },
        ),
        listOf(MenuItem.Display, MenuItem.Settings),
    )

/**
 * ⋮: an Undo · Redo · View icon row on top (as in Chrome's menu), then New ·
 * Open file · Open folder · Save as · (Save all (N)) │ Find & replace ·
 * Insert image… · (Show pictures beside this file…) · Version history │
 * Export PDF · Export images… · Share · Copy as… │ View · Settings. The
 * "Aa" View icon opens the View sheet (wrapping, A4 page, theme, size) and
 * closes the menu; the View row is its twin. Above 1.5× font scale, Copy
 * moves in here too, under the icon row. Show pictures appears only while
 * [picturesNeedFolder]. Save all (N) shows only in folder mode while
 * [unsavedCount] files are unsaved; the ≡ panel keeps its own row.
 * Keyboard shortcuts ("Ctrl F") show only with a hardware keyboard
 * attached, as the Navigator's Ctrl K does.
 *
 * Undo and Redo keep the menu open, so they can be tapped again; each is
 * disabled, and says so, when there is nothing to undo or redo. The rows
 * below them never run off the screen: they are held to the window's
 * height and scroll under the pinned icon row, with a fade at the edge
 * that has more.
 */
@Composable
fun OverflowMenu(
    expanded: Boolean,
    copyInMenu: Boolean,
    onChoose: (MenuItem) -> Unit,
    onDismiss: () -> Unit,
    unsavedCount: Int = 0,
    history: MenuHistoryState = MenuHistoryState(),
    picturesNeedFolder: Boolean = false,
) {
    val hardwareKeyboard = LocalConfiguration.current.keyboard == Configuration.KEYBOARD_QWERTY
    // Measured here, in the editor's window: inside the menu's popup window the keyboard's inset
    // reads as zero, so with the keyboard up the rows ran under it and never scrolled.
    val rowsMaxHeight = menuRowsMaxHeight()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = SS.colors.chrome) {
        HistoryRow(history)
        HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
        ScrollingRows(maxHeight = rowsMaxHeight) {
            menuGroups(copyInMenu, saveAll = unsavedCount > 0, showPictures = picturesNeedFolder).forEachIndexed {
                index,
                group,
                ->
                if (index > 0) HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
                group.forEach { item -> MenuRow(item, unsavedCount, hardwareKeyboard, onChoose) }
            }
        }
    }
}

/** What the ⋮ menu's Undo · Redo · View row shows and does. */
data class MenuHistoryState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onView: () -> Unit = {},
)

@Composable
private fun HistoryRow(history: MenuHistoryState) {
    Row(Modifier.padding(horizontal = 4.dp)) {
        HistoryButton(AppIcons.undo, stringResource(R.string.undo), history.canUndo, history.onUndo)
        HistoryButton(AppIcons.redo, stringResource(R.string.redo), history.canRedo, history.onRedo)
        ViewButton(history.onView)
    }
}

/** "Aa": the View sheet, one tap from ⋮ (Andrew could not find wrapping under "Display"). */
@Composable
private fun ViewButton(onClick: () -> Unit) {
    val label = stringResource(R.string.view_options)
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(SS.dimens.iconButton).semantics { contentDescription = label },
    ) {
        Text("Aa", style = SS.type.bodyStrong, color = SS.colors.ink, modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun HistoryButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Disabled is dimmed and, to TalkBack, "Undo, disabled": never hidden, so the row never jumps.
    val tint = if (enabled) SS.colors.ink else SS.colors.ink.copy(alpha = DISABLED_ALPHA)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(SS.dimens.iconButton)) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

@Composable
private fun MenuRow(
    item: MenuItem,
    unsavedCount: Int,
    hardwareKeyboard: Boolean,
    onChoose: (MenuItem) -> Unit,
) {
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

/**
 * The menu's rows, at most [maxHeight] tall, scrolling inside themselves
 * when they do not fit, with a fade over the edge that has more. Bounding
 * the height also lets them scroll inside the menu's own scroll container.
 */
@Composable
private fun ScrollingRows(
    maxHeight: Dp,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val fade = SS.colors.chrome
    Box(Modifier.heightIn(max = maxHeight).testTag(MENU_ROWS_TAG)) {
        Column(Modifier.verticalScroll(scroll)) { content() }
        if (scroll.canScrollBackward) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(FadeHeight)
                    .background(Brush.verticalGradient(listOf(fade, Color.Transparent))),
            )
        }
        if (scroll.canScrollForward) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(FadeHeight)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, fade))),
            )
        }
    }
}

/**
 * The height left for the rows: the window, less the system bars and the
 * keyboard (the menu hides the keyboard as it opens, but may draw before
 * it has gone, and a keyboard that stays keeps its room), less the menu's
 * margins and the pinned icon row. Read in the editor's window, never the
 * popup's, which hears of no keyboard.
 */
@Composable
private fun menuRowsMaxHeight(): Dp {
    val density = LocalDensity.current
    val window =
        with(density) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
        }
    val insets = WindowInsets.safeDrawing
    val bars = with(density) { (insets.getTop(density) + insets.getBottom(density)).toDp() }
    val room = window - bars - MenuMargins - SS.dimens.iconButton - SS.dimens.hairline
    return room.coerceAtLeast(SS.dimens.iconButton)
}

/** Above and below the menu (Material's 48 dp vertical margin each side) and its own padding. */
private val MenuMargins = 112.dp

/** The menu's scrolling rows, for tests. */
const val MENU_ROWS_TAG = "menu-rows"
private val FadeHeight = 24.dp
private const val DISABLED_ALPHA = 0.38f

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
        MenuItem.CopyAs -> R.string.menu_copy_as
        MenuItem.Display -> R.string.menu_display
        MenuItem.Settings -> R.string.menu_settings
        MenuItem.Copy -> R.string.menu_copy
        MenuItem.InsertImage -> R.string.menu_insert_image
        MenuItem.ShowPictures -> R.string.menu_show_pictures
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
