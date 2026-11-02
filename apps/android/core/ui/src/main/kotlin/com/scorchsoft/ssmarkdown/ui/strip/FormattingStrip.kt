package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SS

private val EdgePadding = 4.dp
private val FadeWidth = 12.dp

/**
 * The formatting strip: the one row that replaces the bottom bar while
 * the keyboard is up (docs/ux-spec.md, "Bottom bar"). It is always one
 * row of 48 dp keys; when they do not fit, the row scrolls, the last
 * visible key is cut at about half, and an edge fade marks the cut.
 * Undo and `{ }` (Code) or Undo and Done (Preview) are pinned at the
 * right. In landscape the filename, unsaved dot and Save ride along,
 * because the header has stepped aside. With the caret in a table, ⋯,
 * ‹ Prev cell and Next cell › are pinned before Undo ([StripLayout.tableKeys]);
 * Prev and Next come back through [onKey], the ⋯ menu's choices through
 * [onTableAction]. The ¶ key opens the block-type menu ([BlockMenuKey]);
 * a choice comes back through [onBlock].
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
) {
    val colors = SS.colors
    val toolbarLabel = stringResource(StripLabels.toolbar)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(SS.dimens.key)
                .background(colors.chrome)
                .drawBehind { drawLine(colors.border, Offset.Zero, Offset(size.width, 0f), SS.dimens.hairline.toPx()) }
                .semantics {
                    contentDescription = toolbarLabel
                    isTraversalGroup = true
                },
    ) {
        state.document?.let { StripDocumentHeader(it) }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            ScrollingKeys(state, maxWidth, onKey, onSymbol, onCursor, onBlock)
        }
        TableKeys(StripLayout.tableKeys(state), onKey, onTableAction)
        StripKeyButton(StripKey.Undo, pressed = false, enabled = state.canUndo) { onKey(StripKey.Undo) }
        if (state.mode == StripMode.Code) {
            SymbolsToggleKey(state.page == StripPage.Symbols) { onKey(StripKey.Symbols) }
        } else {
            StripKeyButton(StripKey.Done, pressed = false, enabled = true) { onKey(StripKey.Done) }
        }
        state.document?.let {
            StripSaveButton(it.save, onSave)
            // Only the Save pill needs air before the screen edge; a 48 dp key carries its own.
            Spacer(Modifier.width(EdgePadding))
        }
    }
}

/** The scrolling part: formatting keys, or on the symbols page the symbols and cursor keys. */
@Composable
private fun ScrollingKeys(
    state: FormattingStripState,
    available: Dp,
    onKey: (StripKey) -> Unit,
    onSymbol: (String) -> Unit,
    onCursor: (CursorStep) -> Unit,
    onBlock: (BlockChoice) -> Unit,
) {
    val symbols = state.mode == StripMode.Code && state.page == StripPage.Symbols
    val keys = StripLayout.scrollingKeys(state.mode)
    val count = if (symbols) StripLayout.symbols.size + CursorStep.entries.size else keys.size
    val scroll = rememberScrollState()
    val width = PeekLayout.keyWidth(available, SS.dimens.key, count, EdgePadding)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .width(PeekLayout.viewportWidth(available, SS.dimens.key, count, EdgePadding))
                .fillMaxHeight()
                .edgeFade(visible = scroll.canScrollForward, color = SS.colors.chrome)
                .horizontalScroll(scroll)
                .padding(start = EdgePadding),
    ) {
        if (symbols) {
            StripLayout.symbols.forEach { SymbolKeyButton(it, width) { onSymbol(it.text) } }
            CursorStep.entries.forEach { CursorKey(it, onCursor, width) }
        } else {
            keys.forEach { key ->
                val enabled = key != StripKey.Redo || state.canRedo
                if (key == StripKey.BlockStyle) {
                    BlockMenuKey(state.block, width, onBlock)
                } else {
                    StripKeyButton(
                        key,
                        pressed = key.toggles && key in state.pressed,
                        enabled = enabled,
                        width = width,
                    ) {
                        onKey(key)
                    }
                }
            }
        }
    }
}

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
