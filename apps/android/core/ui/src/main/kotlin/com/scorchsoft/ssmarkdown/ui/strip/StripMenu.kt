package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.scorchsoft.ssmarkdown.core.ui.SS
import kotlin.math.roundToInt

private val MenuShape = RoundedCornerShape(8.dp)
private val MenuGap = 4.dp
private val MenuMinWidth = 180.dp
private val MenuElevation = 8.dp

/**
 * How every strip menu (¶ and the table ⋯) opens: a popup that **never takes
 * focus**. A focusable popup is a window of its own; giving it focus takes
 * focus from the editor's window, and Android puts the keyboard away. The
 * strip only exists while the keyboard is up, so the menu then vanished with
 * it and the keyboard came straight back: no choice could ever be made
 * (Andrew, 0.2.1, "I've never managed to set a heading"). Not focusable, the
 * editor keeps focus, the caret and the keyboard while the menu is open; a
 * tap outside closes it, and the Back key reaches the keyboard as usual.
 */
internal val StripMenuProperties =
    PopupProperties(
        focusable = false,
        dismissOnBackPress = false,
        dismissOnClickOutside = true,
    )

/**
 * Opens [content] above its anchor (the key it is called next to), so it
 * never lands behind the keyboard, which sits right under the strip. It is
 * capped at the room above the key and scrolls when that is short
 * (landscape with the keyboard up). Only a strip with less room above it
 * than below (never the case over a keyboard) opens it underneath. Rows
 * inside must not be focusable either: use [noFocus].
 */
@Composable
internal fun StripMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var top by remember { mutableIntStateOf(0) }
    // A zero-size probe at the key's top edge: how much window there is above and below the key.
    Box(Modifier.onGloballyPositioned { top = it.positionInWindow().y.roundToInt() })
    if (!open) return
    val density = LocalDensity.current
    val gap = with(density) { MenuGap.roundToPx() }
    val keyHeight = with(density) { SS.dimens.key.roundToPx() }
    val above = top
    val below = LocalWindowInfo.current.containerSize.height - top
    val opensAbove = above >= below - keyHeight
    val room = with(density) { ((if (opensAbove) above else below - keyHeight) - gap).coerceAtLeast(0).toDp() }
    Popup(
        popupPositionProvider = remember(gap, opensAbove) { StripMenuPosition(gap, opensAbove) },
        onDismissRequest = onDismiss,
        properties = StripMenuProperties,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(min = MenuMinWidth)
                    .heightIn(max = room)
                    .shadow(MenuElevation, MenuShape)
                    .background(SS.colors.chrome, MenuShape)
                    .border(SS.dimens.hairline, SS.colors.border, MenuShape)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = MenuGap),
            content = content,
        )
    }
}

/** A strip control never takes focus, from a touch or from a hardware keyboard: the editor keeps it. */
internal fun Modifier.noFocus(): Modifier = focusProperties { canFocus = false }

/**
 * Places the popup [gap] px above the anchor's top ([above]), or below its
 * bottom, with its start edge on the anchor's and inside the window.
 */
internal class StripMenuPosition(
    private val gap: Int,
    private val above: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val start = if (ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val x = start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y =
            if (above) {
                (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
            } else {
                anchorBounds.bottom + gap
            }
        return IntOffset(x, y)
    }
}
