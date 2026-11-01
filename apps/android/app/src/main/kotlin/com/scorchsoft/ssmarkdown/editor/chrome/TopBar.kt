package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.SaveStatusLine
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.ui.AppIcons

private val TitleMinWidth = 120.dp
private val ProgressHeight = 2.dp
private val BadgeShape = RoundedCornerShape(4.dp)

/** Above this font scale the portrait header's two lines fold into one (UX-7). */
const val PORTRAIT_COMPACT_FONT_SCALE = 1.5f

/** Above this font scale the 48 dp landscape bar's title folds into one line (UX-7). */
const val LANDSCAPE_COMPACT_FONT_SCALE = 1.3f

/** Below this title width only the current section is shown ("› Code review"), never stubs. */
private val FullBreadcrumbWidth = 360.dp

/**
 * The 56 dp header (docs/ux-spec.md, "Top bar"): [≡] title · Copy · Save
 * · ⋮, with the reading-position line as its bottom hairline. The title
 * keeps 120 dp and truncates before it can push Copy or Save.
 *
 * It paints the status-bar strip above itself in the chrome colour (UX-27)
 * and grows taller with the font rather than letting its two lines bleed
 * out (UX-7); above [PORTRAIT_COMPACT_FONT_SCALE] the two lines fold into
 * one ([TitleBlock]).
 *
 * @param showFolderButton the `≡` that opens the drawer, on phones in folder mode.
 * @param copyInMenu above 1.5× font scale Copy moves into ⋮; Save never moves.
 */
@Composable
fun TopBar(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    showFolderButton: Boolean,
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
            if (showFolderButton) {
                IconButton(onClick = { onAction(EditorAction.Folder(FolderAction.OpenDrawer)) }) {
                    Icon(AppIcons.menu, stringResource(R.string.open_folder_drawer), tint = SS.colors.ink)
                }
            }
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

/** Copy, Save and ⋮: shared by the portrait header and the landscape bar. */
@Composable
fun RowScope.TopBarActions(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    copyInMenu: Boolean,
) {
    if (!copyInMenu) CopyButton(onAction)
    SaveSlot(
        button = state.save.button,
        onTap = { onAction(EditorAction.SaveTapped) },
        onLongPress = { onAction(EditorAction.SaveLongPressed) },
    )
    Box {
        IconButton(onClick = { onAction(EditorAction.OpenMenu) }) {
            Icon(AppIcons.more, stringResource(R.string.more), tint = SS.colors.ink)
        }
        OverflowMenu(
            expanded = state.overlay == EditorOverlay.Menu,
            copyInMenu = copyInMenu,
            onChoose = { onAction(EditorAction.ChooseMenuItem(it)) },
            onDismiss = { onAction(EditorAction.DismissOverlay) },
        )
        SaveMenu(state, onAction)
        CopyMenu(state, onAction)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyButton(onAction: (EditorAction) -> Unit) {
    val label = stringResource(R.string.copy_all)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(SS.dimens.iconButton)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = label,
                    onLongClickLabel = stringResource(R.string.menu_copy_rich),
                    onLongClick = { onAction(EditorAction.CopyLongPressed) },
                    onClick = { onAction(EditorAction.CopyTapped) },
                ).semantics { contentDescription = label },
    ) {
        Icon(AppIcons.copy, contentDescription = null, tint = SS.colors.ink)
    }
}

/**
 * The name, the red dot and the status line or breadcrumb. Tapping opens the Navigator.
 *
 * @param compact one line instead of two, for a large font in a bar with
 *   little height (UX-7): "name ● · Couldn't save". The status is kept
 *   only when it needs attention, so the unsaved and Save signals survive.
 */
@Composable
fun TitleBlock(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val title = state.title.ifEmpty { stringResource(R.string.untitled) }
    BoxWithConstraints(
        contentAlignment = Alignment.CenterStart,
        modifier =
            modifier
                .heightIn(min = SS.dimens.pillHitArea)
                .clickable(onClickLabel = stringResource(R.string.open_navigator), role = Role.Button) {
                    onAction(EditorAction.TitleTapped)
                }.padding(horizontal = SS.dimens.grid * 2, vertical = SS.dimens.grid),
    ) {
        val wide = maxWidth >= FullBreadcrumbWidth
        // The second line says "Unsaved" itself in these states, so the dot stays silent (UX-30).
        val lineSaysUnsaved =
            state.save.status.let {
                it == SaveStatusLine.Unsaved ||
                    it is SaveStatusLine.UnsavedReadOnly
            }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = SS.type.bodyStrong,
                    color = SS.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.save.showDirtyDot) UnsavedDot(labelled = compact || !lineSaysUnsaved)
                if (compact) CompactStatus(state.save.status, Modifier.weight(1f, fill = false))
            }
            if (!compact) SecondLine(state, wide, onAction)
        }
    }
}

/** The one-line status: only what needs attention, after the name. */
@Composable
private fun CompactStatus(
    status: SaveStatusLine,
    modifier: Modifier,
) {
    val worth =
        status is SaveStatusLine.Failed ||
            status is SaveStatusLine.AutosavePaused ||
            status is SaveStatusLine.ReadOnly ||
            status is SaveStatusLine.UnsavedReadOnly ||
            status == SaveStatusLine.Saving
    if (!worth) return
    Text(
        "· " + statusText(status),
        style = SS.type.meta,
        color = if (status.isError()) SS.colors.needsYouText else SS.colors.inkMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private fun SaveStatusLine.isError(): Boolean = this is SaveStatusLine.Failed || this is SaveStatusLine.AutosavePaused

@Composable
private fun SecondLine(
    state: EditorUiState,
    wide: Boolean,
    onAction: (EditorAction) -> Unit,
) {
    val status = state.save.status
    val error = status is SaveStatusLine.Failed
    if (state.breadcrumb.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (status is SaveStatusLine.ReadOnly || status is SaveStatusLine.UnsavedReadOnly) ReadOnlyBadge()
            Text(
                statusText(status),
                style = SS.type.meta,
                color = if (error) SS.colors.needsYouText else SS.colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.bigFile) BigFileChip { onAction(EditorAction.BigFileChipTapped) }
        }
        return
    }
    val suffix = breadcrumbSuffix(status)
    val crumbs = if (wide) state.breadcrumb else listOf(state.breadcrumb.last())
    val separator = stringResource(R.string.breadcrumb_separator)
    val text =
        buildAnnotatedString {
            if (!wide) append(separator.trimStart())
            crumbs.forEachIndexed { i, crumb ->
                if (i > 0) append(separator)
                if (i ==
                    crumbs.lastIndex
                ) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(crumb) }
                } else {
                    append(crumb)
                }
            }
            suffix?.let { append(" · $it") }
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text,
            style = SS.type.meta,
            color = if (error) SS.colors.needsYouText else SS.colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // UX-14: Big-file mode stays visible when a breadcrumb replaces the status line.
        if (state.bigFile) BigFileChip { onAction(EditorAction.BigFileChipTapped) }
    }
}

@Composable
private fun ReadOnlyBadge() {
    Text(
        stringResource(R.string.badge_read_only),
        style = SS.type.caption,
        color = SS.colors.caution,
        modifier = Modifier.background(SS.colors.cautionBg, BadgeShape).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun BigFileChip(onTap: () -> Unit) {
    Text(
        stringResource(R.string.big_file_mode),
        style = SS.type.caption,
        color = SS.colors.caution,
        modifier =
            Modifier
                .background(SS.colors.cautionBg, BadgeShape)
                .clickable(role = Role.Button, onClick = onTap)
                .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The header's bottom hairline, doubling as a neutral 2 dp reading-position line. */
@Composable
fun ReadingLine(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(ProgressHeight).background(SS.colors.border)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(ProgressHeight).background(SS.colors.gutter))
    }
}
