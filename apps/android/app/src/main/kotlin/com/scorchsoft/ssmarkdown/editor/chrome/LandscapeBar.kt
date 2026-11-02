package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.ui.AppIcons

private val LandscapeBarHeight = 48.dp

/**
 * Phone landscape: the header and the mode switch merge into one 48 dp
 * bar (title · Code/Split/Preview · Save · Copy · ⋮), with no bottom bar,
 * so ~250 dp of text remains even with a 3-button nav bar (ux-spec
 * "System bars and insets"). Above [LANDSCAPE_COMPACT_FONT_SCALE] the
 * title folds to one line and the bar may grow, so the name, the dot and
 * a failed save are never clipped (UX-7).
 */
@Composable
fun LandscapeBar(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    folderButton: FolderButton?,
    copyInMenu: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(SS.colors.chrome).statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = LandscapeBarHeight).padding(horizontal = SS.dimens.grid),
        ) {
            folderButton?.let { FolderButtonView(it, onAction) }
            TitleBlock(
                state,
                onAction,
                Modifier.weight(1f).widthIn(min = TitleMinWidth),
                compact = LocalDensity.current.fontScale > LANDSCAPE_COMPACT_FONT_SCALE,
            )
            ModeSwitcher(
                mode = state.mode,
                onSelect = { onAction(EditorAction.SelectMode(it)) },
                iconOnly = true,
                modes = if (state.isMarkdown) ViewMode.entries else listOf(ViewMode.Code),
            )
            TopBarActions(state, onAction, copyInMenu)
        }
        ReadingLine(state.readingFraction)
    }
}
