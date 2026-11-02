package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.settings.CopyFormat
import com.scorchsoft.ssmarkdown.ui.AppIcons

/** The title's minimum width; Save leaves it this much before taking more (UX2-1). */
internal val TitleMinWidth = 120.dp
private val ProgressHeight = 2.dp

/** Above this font scale the portrait header's two lines fold into one (UX-7). */
const val PORTRAIT_COMPACT_FONT_SCALE = 1.5f

/** Above this font scale the 48 dp landscape bar's title folds into one line (UX-7). */
const val LANDSCAPE_COMPACT_FONT_SCALE = 1.3f

/**
 * The 56 dp header (docs/ux-spec.md, "Top bar"): [≡] title · Save · Copy
 * · ⋮, with the reading-position line as its bottom hairline. The title
 * keeps 120 dp and truncates before it can push Copy or Save.
 *
 * It paints the status-bar strip above itself in the chrome colour (UX-27)
 * and grows taller with the font rather than letting its two lines bleed
 * out (UX-7); above [PORTRAIT_COMPACT_FONT_SCALE] the two lines fold into
 * one ([TitleBlock]).
 *
 * @param folderButton the `≡` in folder mode: the drawer on phones, the
 *   docked panel on tablets; null outside folder mode.
 * @param copyInMenu above 1.5× font scale Copy moves into ⋮; Save never moves.
 */
@Composable
fun TopBar(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    folderButton: FolderButton?,
    copyInMenu: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(SS.colors.chrome).statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SS.dimens.topBar)
                    .padding(start = SS.dimens.grid, end = SS.dimens.grid),
        ) {
            folderButton?.let { FolderButtonView(it, onAction) }
            TitleBlock(
                state,
                onAction,
                Modifier.weight(1f).widthIn(min = TitleMinWidth),
                compact = LocalDensity.current.fontScale > PORTRAIT_COMPACT_FONT_SCALE,
            )
            TopBarActions(state, onAction, copyInMenu)
        }
        ReadingLine(state.readingFraction)
    }
}

/** What the header's `≡` does in folder mode. */
enum class FolderButton {
    /** Phones: open the modal drawer. */
    OpenDrawer,

    /** Tablets: collapse the docked panel, giving its width back to the text (UX2-4). */
    HidePanel,

    /** Tablets: bring the collapsed panel back. */
    ShowPanel,
}

/** The `≡` itself, shared by the portrait header and the landscape bar. */
@Composable
fun FolderButtonView(
    kind: FolderButton,
    onAction: (EditorAction) -> Unit,
) {
    val (action, label) =
        when (kind) {
            FolderButton.OpenDrawer -> FolderAction.OpenDrawer to R.string.open_folder_drawer
            FolderButton.HidePanel -> FolderAction.TogglePanel to R.string.hide_folder_panel
            FolderButton.ShowPanel -> FolderAction.TogglePanel to R.string.show_folder_panel
        }
    IconButton(onClick = { onAction(EditorAction.Folder(action)) }) {
        Icon(AppIcons.menu, stringResource(label), tint = SS.colors.ink)
    }
}

/** Save, Copy and ⋮: shared by the portrait header and the landscape bar. */
@Composable
fun RowScope.TopBarActions(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    copyInMenu: Boolean,
) {
    // Save sits before Copy. The slot keeps a fixed width so neither moves
    // between states, and its content is right-aligned against Copy, so the
    // slack falls on the title side, where it reads as title space. With
    // Copy first, the clean tick sat alone with a 60 dp hole beside it.
    SaveSlot(
        button = state.save.button,
        onTap = { onAction(EditorAction.SaveTapped) },
        onLongPress = { onAction(EditorAction.SaveLongPressed) },
        // The Row measures Save before Copy, ⋮ and the weighted title, so
        // Save's room still includes them: leave them their 48 dp each and the
        // title its minimum.
        reserve = TitleMinWidth + SS.dimens.iconButton * (if (copyInMenu) 1 else 2),
    )
    if (!copyInMenu) {
        Box {
            CopyButton(state.copyFormat, onAction)
            CopyChooser(state, onAction)
        }
    }
    Box {
        IconButton(onClick = { onAction(EditorAction.OpenMenu) }) {
            Icon(AppIcons.more, stringResource(R.string.more), tint = SS.colors.ink)
        }
        OverflowMenu(
            expanded = state.overlay == EditorOverlay.Menu,
            copyInMenu = copyInMenu,
            onChoose = { onAction(EditorAction.ChooseMenuItem(it)) },
            onDismiss = { onAction(EditorAction.DismissOverlay) },
            unsavedCount = state.folder?.unsavedCount ?: 0,
            picturesNeedFolder = state.preview.picturesNeedFolder,
            history =
                MenuHistoryState(
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    onUndo = { onAction(EditorAction.MenuHistory(redo = false)) },
                    onRedo = { onAction(EditorAction.MenuHistory(redo = true)) },
                    onView = { onAction(EditorAction.OpenView(highlight = null)) },
                ),
        )
        SaveMenu(state, onAction)
        // With Copy moved into ⋮, its chooser opens from ⋮.
        if (copyInMenu) CopyChooser(state, onAction)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyButton(
    last: CopyFormat,
    onAction: (EditorAction) -> Unit,
) {
    val label = stringResource(R.string.copy_all)
    val again =
        stringResource(
            if (last ==
                CopyFormat.Formatted
            ) {
                R.string.copy_again_formatted
            } else {
                R.string.copy_again_markdown
            },
        )
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(SS.dimens.iconButton)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = label,
                    onLongClickLabel = again,
                    onLongClick = { onAction(EditorAction.CopyLongPressed) },
                    onClick = { onAction(EditorAction.CopyTapped) },
                ).semantics { contentDescription = label },
    ) {
        Icon(AppIcons.copy, contentDescription = null, tint = SS.colors.ink)
    }
}

/** The header's bottom hairline, doubling as a neutral 2 dp reading-position line. */
@Composable
fun ReadingLine(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(ProgressHeight).background(SS.colors.border)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(ProgressHeight).background(SS.colors.gutter))
    }
}
