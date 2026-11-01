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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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

/** The title's minimum width; Save leaves it this much before taking more (UX2-1). */
internal val TitleMinWidth = 120.dp
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
        // The Row measures Save before ⋮ and the weighted title, so Save's room
        // still includes both: leave ⋮ its 48 dp and the title its minimum.
        reserve = TitleMinWidth + SS.dimens.iconButton,
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            contentAlignment = Alignment.CenterStart,
            modifier =
                Modifier
                    .weight(1f)
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
                TitleFirstRow {
                    Text(
                        title,
                        style = SS.type.bodyStrong,
                        color = SS.colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.layoutId(TITLE_ID),
                    )
                    if (state.save.showDirtyDot) {
                        UnsavedDot(Modifier.layoutId(DOT_ID), labelled = compact || !lineSaysUnsaved)
                    }
                    if (compact) CompactStatus(state.save.status, Modifier.layoutId(STATUS_ID))
                }
                if (!compact) SecondLine(state, wide)
            }
        }
        // UX-14 / UX2-5: Big-file mode is its own 48 dp target beside the title, never
        // nested in the title's Navigator tap, and shown in the compact header too.
        if (state.bigFile) BigFileChip { onAction(EditorAction.BigFileChipTapped) }
    }
}

private const val TITLE_ID = "title"
private const val DOT_ID = "dot"
private const val STATUS_ID = "status"
private val TitleGap = 6.dp

/**
 * The name, then the unsaved dot, then (in the one-line header) the status.
 * The name is first in line for the room: when name and status cannot both
 * be whole on one line, the status moves under the name rather than
 * squeezing it, and the bar grows (UX-7). Sharing the line equally let
 * "· Unsaved · read-only" cut a long name to two letters on a 360 dp phone
 * at 2× font in folder mode.
 */
@Composable
private fun TitleFirstRow(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val gap = TitleGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val dot = measurables.firstOrNull { it.layoutId == DOT_ID }?.measure(loose)
        val title = measurables.first { it.layoutId == TITLE_ID }
        val status = measurables.firstOrNull { it.layoutId == STATUS_ID }
        val room = (constraints.maxWidth - (dot?.let { it.width + gap } ?: 0)).coerceAtLeast(0)
        val titleWanted = title.maxIntrinsicWidth(constraints.maxHeight)
        val statusWanted = status?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
        val oneLine = status == null || titleWanted + gap + statusWanted <= room
        val titlePlaceable = title.measure(loose.copy(maxWidth = minOf(titleWanted, room)))
        val statusPlaceable =
            status?.measure(
                loose.copy(maxWidth = if (oneLine) statusWanted else constraints.maxWidth),
            )
        val firstRow = listOfNotNull(titlePlaceable, dot, statusPlaceable.takeIf { oneLine })
        val firstHeight = firstRow.maxOf { it.height }
        val below = statusPlaceable.takeIf { !oneLine }
        val width =
            maxOf(firstRow.sumOf { it.width } + gap * (firstRow.size - 1), below?.width ?: 0)
                .coerceAtMost(constraints.maxWidth)
        layout(width, firstHeight + (below?.height ?: 0)) {
            var x = 0
            firstRow.forEach {
                it.placeRelative(x, (firstHeight - it.height) / 2)
                x += it.width + gap
            }
            below?.placeRelative(0, firstHeight)
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

@Composable
private fun SecondLine(
    state: EditorUiState,
    wide: Boolean,
) {
    val status = state.save.status
    val error = status.isError()
    if (state.breadcrumb.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val badge = status is SaveStatusLine.ReadOnly || status is SaveStatusLine.UnsavedReadOnly
            if (badge) ReadOnlyBadge()
            Text(
                statusText(status, besideBadge = badge),
                style = SS.type.meta,
                color = if (error) SS.colors.needsYouText else SS.colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

/**
 * The Big-file chip: a small caution badge drawn inside a full 48 dp
 * target (UX2-5), so a slightly-off tap lands on it rather than on the title.
 */
@Composable
private fun BigFileChip(onTap: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .testTag(BIG_FILE_CHIP_TAG)
                .sizeIn(minWidth = SS.dimens.pillHitArea, minHeight = SS.dimens.pillHitArea)
                .clickable(role = Role.Button, onClick = onTap)
                .padding(horizontal = 4.dp),
    ) {
        Text(
            stringResource(R.string.big_file_mode),
            style = SS.type.caption,
            color = SS.colors.caution,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.background(SS.colors.cautionBg, BadgeShape).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** The Big-file chip's test tag, for its touch-target test. */
const val BIG_FILE_CHIP_TAG = "big-file-chip"

/** The header's bottom hairline, doubling as a neutral 2 dp reading-position line. */
@Composable
fun ReadingLine(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(ProgressHeight).background(SS.colors.border)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(ProgressHeight).background(SS.colors.gutter))
    }
}
