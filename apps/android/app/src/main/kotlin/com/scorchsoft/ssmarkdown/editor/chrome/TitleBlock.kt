package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.SaveStatusLine

/*
 * The header's title: the name, the unsaved dot, the status line or
 * breadcrumb, and the Big-file chip. Shared by the portrait header and the
 * landscape bar (TopBar.kt holds the bars themselves).
 */

private val BadgeShape = RoundedCornerShape(4.dp)

/** Below this title width only the current section is shown ("› Code review"), never stubs. */
private val FullBreadcrumbWidth = 360.dp

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
            // The status says "Unsaved" itself in these states, in either header, so the dot stays silent (UX-30).
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
                        UnsavedDot(Modifier.layoutId(DOT_ID), labelled = !lineSaysUnsaved)
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

/**
 * The one-line status: only what needs attention, after the name. Unsaved
 * is one of those: the red dot alone would make it a colour-only signal
 * (rubric #10), so the word is there too and moves under a long name.
 */
@Composable
private fun CompactStatus(
    status: SaveStatusLine,
    modifier: Modifier,
) {
    val worth =
        status == SaveStatusLine.Unsaved ||
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
        }
    val color = if (error) SS.colors.needsYouText else SS.colors.inkMuted
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The section name gives way first; the state ("Unsaved", "Couldn't save · …") is never cut.
        Text(
            text,
            style = SS.type.meta,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        suffix?.let { Text(" · $it", style = SS.type.meta, color = color, maxLines = 1, softWrap = false) }
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
