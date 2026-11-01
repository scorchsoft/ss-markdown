package com.scorchsoft.ssmarkdown.editor.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.editor.chrome.PinchPill
import com.scorchsoft.ssmarkdown.editor.chrome.ZoomChip

/** Split goes side by side from this width (ux-spec "Layouts"). */
val SideBySideWidth = 600.dp

private val DividerTouch = 48.dp
private val DividerHandle = 14.dp
private val HandleLength = 36.dp
private const val MIN_FRACTION = 0.2f
private const val MAX_FRACTION = 0.8f
private const val STEP_FRACTION = 0.1f
private const val EVEN_SPLIT = 0.5f

/**
 * The page area in Code, Split or Preview. The Code pane and the Preview
 * pane are each hosted with `movableContentOf`, so switching modes or
 * rotating moves the **same** `MarkdownCodeView` and WebView instead of
 * rebuilding them (robustness §5a, rule 2): cursor, selection, scroll,
 * undo and the keyboard connection survive.
 *
 * @param codePane draws the Code pane into the modifier it is given.
 * @param previewPane draws the Preview pane; see [PreviewHostState].
 * @param imeVisible in portrait Split the preview collapses to a one-line strip while the keyboard is up.
 */
@Composable
fun EditorPanes(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    codePane: @Composable (Modifier) -> Unit,
    previewPane: @Composable (PreviewHostState) -> Unit,
    previewState: PreviewHostState,
    imeVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val code = remember(codePane) { movableContentOf { m: Modifier -> codePane(m) } }
    val preview =
        remember(previewPane) {
            movableContentOf {
                s: PreviewHostState,
                m: Modifier,
                ->
                Box(m) { previewPane(s) }
            }
        }
    val showPreview = state.isMarkdown
    BoxWithConstraints(modifier.fillMaxSize().background(SS.colors.bg)) {
        val sideBySide = maxWidth >= SideBySideWidth
        when {
            state.mode == ViewMode.Code || !showPreview -> {
                CodeWithChip(state, onAction, code, Modifier.fillMaxSize())
            }

            state.mode == ViewMode.Preview -> {
                PreviewWithChip(
                    state,
                    onAction,
                    preview,
                    previewState,
                    Modifier.fillMaxSize(),
                )
            }

            else -> {
                Split(state, onAction, code, preview, previewState, sideBySide, imeVisible)
            }
        }
        state.pinchPercent?.let { PinchPill(it, Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) }
    }
}

@Composable
private fun CodeWithChip(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    code: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
    Box(modifier) {
        code(Modifier.fillMaxSize())
        if (state.showCodeZoomChip) {
            ZoomChip(state.codeZoom, {
                onAction(EditorAction.ResetZoom(Pane.Code))
            }, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}

@Composable
private fun PreviewWithChip(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    preview: @Composable (PreviewHostState, Modifier) -> Unit,
    previewState: PreviewHostState,
    modifier: Modifier,
) {
    Box(modifier) {
        preview(previewState, Modifier.fillMaxSize())
        if (state.showPreviewZoomChip) {
            ZoomChip(state.previewZoom, {
                onAction(EditorAction.ResetZoom(Pane.Preview))
            }, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}

@Suppress("LongParameterList") // Both movable panes plus the layout facts; all from one caller.
@Composable
private fun Split(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    code: @Composable (Modifier) -> Unit,
    preview: @Composable (PreviewHostState, Modifier) -> Unit,
    previewState: PreviewHostState,
    sideBySide: Boolean,
    imeVisible: Boolean,
) {
    // The divider position is remembered separately for each orientation (ux-spec "Layouts").
    var fraction by rememberSaveable(sideBySide) { mutableFloatStateOf(EVEN_SPLIT) }
    val setFraction: (Float) -> Unit = { fraction = it.coerceIn(MIN_FRACTION, MAX_FRACTION) }
    if (!sideBySide && imeVisible) {
        // Portrait with the keyboard up: editing gets the space; the preview is a one-line breadcrumb strip.
        Column(Modifier.fillMaxSize()) {
            CodeWithChip(state, onAction, code, Modifier.weight(1f).fillMaxWidth())
            CollapsedPreviewStrip(state.breadcrumb.lastOrNull())
        }
        return
    }
    val orientation = if (sideBySide) Orientation.Horizontal else Orientation.Vertical
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val total = if (sideBySide) maxWidth else maxHeight
        val first = @Composable { m: Modifier -> CodeWithChip(state, onAction, code, m) }
        val second = @Composable { m: Modifier -> PreviewWithChip(state, onAction, preview, previewState, m) }
        if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
                first(Modifier.weight(fraction).fillMaxHeight())
                DividerBand(orientation)
                second(Modifier.weight(1f - fraction).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                first(Modifier.weight(fraction).fillMaxWidth())
                DividerBand(orientation)
                second(Modifier.weight(1f - fraction).fillMaxWidth())
            }
        }
        DividerTouchArea(orientation, total, fraction, setFraction)
    }
}

/** The visible 14 dp band with its grip. */
@Composable
private fun DividerBand(orientation: Orientation) {
    val vertical = orientation == Orientation.Vertical
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            (
                if (vertical) {
                    Modifier.fillMaxWidth().height(
                        DividerHandle,
                    )
                } else {
                    Modifier.fillMaxHeight().width(DividerHandle)
                }
            ).background(SS.colors.chrome),
    ) {
        Box(
            Modifier
                .size(if (vertical) HandleLength else 4.dp, if (vertical) 4.dp else HandleLength)
                .background(SS.colors.gutter, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * The divider's 48 dp invisible touch area, centred on the 14 dp band and
 * laid over the panes so it costs no text space, with TalkBack actions
 * "Make top larger / smaller" (review 3).
 */
@Composable
private fun DividerTouchArea(
    orientation: Orientation,
    total: Dp,
    fraction: Float,
    onFraction: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val span = (total - DividerHandle).coerceAtLeast(1.dp)
    val spanPx = with(density) { span.toPx() }
    val drag = rememberDraggableState { delta -> onFraction(fraction + delta / spanPx) }
    val larger = stringResource(R.string.split_top_larger)
    val smaller = stringResource(R.string.split_top_smaller)
    val label = stringResource(R.string.split_divider)
    val start = span * fraction + DividerHandle / 2 - DividerTouch / 2
    val vertical = orientation == Orientation.Vertical
    Box(
        (
            if (vertical) {
                Modifier
                    .offset(
                        y = start,
                    ).fillMaxWidth()
                    .height(DividerTouch)
            } else {
                Modifier.offset(x = start).fillMaxHeight().width(DividerTouch)
            }
        ).draggable(drag, orientation)
            .semantics {
                contentDescription = label
                customActions =
                    listOf(
                        CustomAccessibilityAction(larger) {
                            onFraction(fraction + STEP_FRACTION)
                            true
                        },
                        CustomAccessibilityAction(smaller) {
                            onFraction(fraction - STEP_FRACTION)
                            true
                        },
                    )
            },
    )
}

@Composable
private fun CollapsedPreviewStrip(section: String?) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(
                    SS.dimens.row,
                ).background(SS.colors.chrome)
                .padding(horizontal = 16.dp),
    ) {
        Text(
            section ?: stringResource(R.string.mode_preview),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
            maxLines = 1,
        )
    }
}
