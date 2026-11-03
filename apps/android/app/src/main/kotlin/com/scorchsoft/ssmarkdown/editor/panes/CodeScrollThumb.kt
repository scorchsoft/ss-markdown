package com.scorchsoft.ssmarkdown.editor.panes

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.components.FastScrollState
import com.scorchsoft.ssmarkdown.core.ui.components.FastScrollThumb
import com.scorchsoft.ssmarkdown.document.OutlineTracker
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView

/**
 * The Code pane's fast-scroll thumb over [view]: the view's scroll shows
 * and moves it (by line index, through the height map, so a 20 MB file
 * stays steady), and a drag moves the view to the line it names without
 * touching the caret. The pane leads while dragged, as for any touch, so
 * in Split the other pane follows through the ordinary scroll sync.
 */
@Composable
internal fun CodeScrollThumb(
    view: MarkdownCodeView,
    outline: () -> OutlineTracker?,
    state: FastScrollState,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val currentOutline = rememberUpdatedState(outline)
    val bubble =
        remember(state, resources) {
            SectionBubble(state) { line -> resources.getString(R.string.fast_scroll_line, line + 1) }
        }
    DisposableEffect(view, state) {
        view.setOnScrollChangeListener(
            View.OnScrollChangeListener { _, _, top, _, oldTop ->
                state.suppressed = view.isSelectionInUse
                val fast = view.fastScroll
                // A horizontal pan in No wrap moves only scrollX: it never shows the thumb.
                if (top != oldTop) state.onScrolled(fast.fraction, fast.visibleFraction)
                if (state.isDragging) bubble.update(currentOutline.value(), view.anchorLine)
            },
        )
        state.onMeasured(view.fastScroll.fraction, view.fastScroll.visibleFraction)
        onDispose { view.setOnScrollChangeListener(null) }
    }
    FastScrollThumb(
        state = state,
        onDrag = view.fastScroll::dragTo,
        modifier = modifier,
        onDragStart = {
            view.fastScroll.begin()
            bubble.update(currentOutline.value(), view.anchorLine)
        },
        onDragEnd = view.fastScroll::end,
    )
}
