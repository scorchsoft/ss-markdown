package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SS

private val EdgePadding = 4.dp
private val FadeWidth = 12.dp

/**
 * The formatting strip: the bar that replaces the bottom bar while the
 * keyboard is up (docs/ux-spec.md, "Bottom bar"). By default it is one row
 * of 48 dp keys; when they do not fit, the row scrolls, the last visible
 * key is cut at about half, and an edge fade marks the cut. Undo and `{ }`
 * (Code) or Undo and Done (Preview) are pinned at the right. In landscape
 * the filename, unsaved dot and Save ride along, because the header has
 * stepped aside. With the caret in a table, ⋯, ‹ Prev cell and Next cell ›
 * are pinned before Undo ([StripLayout.tableKeys]); Prev and Next come back
 * through [onKey], the ⋯ menu's choices through [onTableAction]. The ¶ key
 * opens the block-type menu ([BlockMenuKey]); a choice comes back through
 * [onBlock].
 *
 * With [rows] at [StripRows.Two] (Settings → Formatting bar, after
 * [StripLayout.rowsFor]) the strip is two key-height rows: the top row
 * holds the keys that fit before the pinned group, which keeps its place at
 * the right, and the bottom row carries on with the rest across the full
 * width ([StripLayout.splitRows]). A bottom row that still does not fit
 * scrolls, with the same cut and fade. When every key fits in the top row,
 * the strip stays one row.
 *
 * Stateless: [state] in, callbacks out. The editor runs each key's
 * command, flips [FormattingStripState.page] with [StripPaging], and
 * commits the keyboard's composing word first (research R6).
 */
@Composable
fun FormattingStrip(
    state: FormattingStripState,
    onKey: (StripKey) -> Unit,
    onSymbol: (String) -> Unit,
    onCursor: (CursorStep) -> Unit,
    modifier: Modifier = Modifier,
    onSave: () -> Unit = {},
    onTableAction: (TableAction) -> Unit = {},
    onBlock: (BlockChoice) -> Unit = {},
    rows: StripRows = StripRows.One,
) {
    val colors = SS.colors
    val toolbarLabel = stringResource(StripLabels.toolbar)
    val callbacks = StripCallbacks(onKey, onSymbol, onCursor, onSave, onTableAction, onBlock)
    // One hairline along the top, however many rows: two rows are parted by the keys' own air.
    val frame =
        modifier
            .fillMaxWidth()
            .background(colors.chrome)
            .drawBehind { drawLine(colors.border, Offset.Zero, Offset(size.width, 0f), SS.dimens.hairline.toPx()) }
            .semantics {
                contentDescription = toolbarLabel
                isTraversalGroup = true
            }
    if (rows == StripRows.Two) {
        TwoRowStrip(state, callbacks, frame)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = frame.height(SS.dimens.key)) {
            state.document?.let { StripDocumentHeader(it) }
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
                KeyRun(state, StripLayout.items(state), maxWidth, EdgePadding, callbacks)
            }
            PinnedGroup(state, callbacks)
        }
    }
}

/** The strip's callbacks, passed down as one. */
@Immutable
private class StripCallbacks(
    val onKey: (StripKey) -> Unit,
    val onSymbol: (String) -> Unit,
    val onCursor: (CursorStep) -> Unit,
    val onSave: () -> Unit,
    val onTableAction: (TableAction) -> Unit,
    val onBlock: (BlockChoice) -> Unit,
)

/** Table keys, Undo, `{ }` or Done, and in landscape the Save pill: the keys that never scroll. */
@Composable
private fun PinnedGroup(
    state: FormattingStripState,
    callbacks: StripCallbacks,
) {
    val onKey = callbacks.onKey
    Row(verticalAlignment = Alignment.CenterVertically) {
        TableKeys(StripLayout.tableKeys(state), onKey, callbacks.onTableAction)
        StripKeyButton(StripKey.Undo, pressed = false, enabled = state.canUndo) { onKey(StripKey.Undo) }
        if (state.mode == StripMode.Code) {
            SymbolsToggleKey(state.page == StripPage.Symbols) { onKey(StripKey.Symbols) }
        } else {
            StripKeyButton(StripKey.Done, pressed = false, enabled = true) { onKey(StripKey.Done) }
        }
        state.document?.let {
            StripSaveButton(it.save, callbacks.onSave)
            // Only the Save pill needs air before the screen edge; a 48 dp key carries its own.
            Spacer(Modifier.width(EdgePadding))
        }
    }
}

/** The parts of a two-row strip, in TalkBack's reading order: the top row left to right, then the bottom row. */
private enum class Part { Lead, Top, Pinned, Bottom }

/**
 * Two rows: the document header (if any), the top row's keys and the
 * pinned group across the top, the rest of the keys below. The header and
 * the pinned group are measured first, so the top row takes exactly the
 * room between them in the same frame, with no second pass.
 */
@Composable
private fun TwoRowStrip(
    state: FormattingStripState,
    callbacks: StripCallbacks,
    modifier: Modifier,
) {
    val items = StripLayout.items(state)
    val key = SS.dimens.key
    SubcomposeLayout(modifier) { constraints ->
        val keyPx = key.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else keyPx * (items.size + PINNED_ESTIMATE)
        val loose = Constraints(maxWidth = width, maxHeight = keyPx)

        fun row(room: Int) = Constraints(minWidth = room, maxWidth = room, minHeight = keyPx, maxHeight = keyPx)

        fun part(
            which: Part,
            content: @Composable () -> Unit,
        ) = subcompose(which) { Box(Modifier.ordered(which), contentAlignment = Alignment.CenterStart) { content() } }

        val lead = part(Part.Lead) { state.document?.let { DocumentHeaderRow(it) } }.map { it.measure(loose) }
        val leadWidth = lead.sumOf { it.width }
        val pinned =
            part(Part.Pinned) { PinnedGroup(state, callbacks) }
                .map { it.measure(loose.copy(maxWidth = (width - leadWidth).coerceAtLeast(0))) }
        val pinnedWidth = pinned.sumOf { it.width }
        val topRoom = (width - leadWidth - pinnedWidth).coerceAtLeast(0)
        val split = StripLayout.splitRows(items.size, topRoom.toDp(), key)
        val top =
            part(Part.Top) { KeyRun(state, items.subList(0, split.top), topRoom.toDp(), 0.dp, callbacks) }
                .map { it.measure(row(topRoom)) }
        val bottom =
            if (split.bottom == 0) {
                emptyList()
            } else {
                part(Part.Bottom) { KeyRun(state, items.subList(split.top, items.size), width.toDp(), 0.dp, callbacks) }
                    .map { it.measure(row(width)) }
            }
        layout(width, if (bottom.isEmpty()) keyPx else keyPx * 2) {
            lead.forEach { it.placeRelative(0, (keyPx - it.height) / 2) }
            top.forEach { it.placeRelative(leadWidth, 0) }
            pinned.forEach { it.placeRelative(width - pinnedWidth, (keyPx - it.height) / 2) }
            bottom.forEach { it.placeRelative(0, keyPx) }
        }
    }
}

/** Each part of a two-row strip is a traversal group of its own, read in [Part] order. */
private fun Modifier.ordered(part: Part): Modifier =
    semantics {
        isTraversalGroup = true
        traversalIndex = part.ordinal.toFloat()
    }

@Composable
private fun DocumentHeaderRow(document: StripDocument) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(SS.dimens.key)) {
        StripDocumentHeader(document)
    }
}

/**
 * A run of keys in one row: all of them when they fit in [available],
 * otherwise a scrolling row whose last visible key is cut at about half,
 * with a fade at the cut ([PeekLayout]).
 */
@Composable
private fun KeyRun(
    state: FormattingStripState,
    items: List<StripItem>,
    available: Dp,
    padding: Dp,
    callbacks: StripCallbacks,
) {
    val scroll = rememberScrollState()
    val count = items.size
    val width = PeekLayout.keyWidth(available, SS.dimens.key, count, padding)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .width(PeekLayout.viewportWidth(available, SS.dimens.key, count, padding))
                .fillMaxHeight()
                .edgeFade(visible = scroll.canScrollForward, color = SS.colors.chrome)
                .horizontalScroll(scroll)
                .padding(start = padding),
    ) {
        items.forEach { item -> ItemKey(item, state, width, callbacks) }
    }
}

/** One key of the scrolling part: a formatting key (¶ with its menu), a symbol or a cursor key. */
@Composable
private fun ItemKey(
    item: StripItem,
    state: FormattingStripState,
    width: Dp,
    callbacks: StripCallbacks,
) {
    when (item) {
        is StripItem.Symbol -> {
            SymbolKeyButton(item.symbol, width) { callbacks.onSymbol(item.symbol.text) }
        }

        is StripItem.Cursor -> {
            CursorKey(item.step, callbacks.onCursor, width)
        }

        is StripItem.Key -> {
            val key = item.key
            if (key == StripKey.BlockStyle) {
                BlockMenuKey(state.block, width, callbacks.onBlock)
            } else {
                StripKeyButton(
                    key,
                    pressed = key.toggles && key in state.pressed,
                    enabled = key != StripKey.Redo || state.canRedo,
                    width = width,
                ) {
                    callbacks.onKey(key)
                }
            }
        }
    }
}

/** Room for the pinned group when the strip is measured without a width bound (never in the app). */
private const val PINNED_ESTIMATE = 2

/** Fades the right edge into the strip's colour while there is more to scroll to. */
private fun Modifier.edgeFade(
    visible: Boolean,
    color: Color,
): Modifier =
    drawWithContent {
        drawContent()
        if (visible) {
            // Over the last quarter of a 48 dp key, at the edge by the pinned keys: the cut
            // key reads as half a key that fades at the cut, not as a key faded early.
            val fade = FadeWidth.toPx()
            val left = size.width - fade
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0f), color),
                        startX = left,
                        endX = size.width,
                    ),
                topLeft = Offset(left, 0f),
                size = Size(fade, size.height),
            )
        }
    }
