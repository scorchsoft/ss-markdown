package com.scorchsoft.ssmarkdown.editor.panes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.scorchsoft.ssmarkdown.settings.SplitTyping

/** Split can go side by side only from this pane width (ux-spec "Layouts"). */
val SideBySideWidth = 600.dp

/**
 * Whether Split puts its panes side by side (else top and bottom). It
 * follows the window's shape (ux-spec "Layouts"): a window wider than it is
 * tall splits side by side, a taller one stacks, so a 600 x 960 dp tablet in
 * portrait stacks and the same tablet in landscape does not. The pane also
 * needs [SideBySideWidth], or each half would be narrower than a phone.
 *
 * The shape is the window's, not the page's: the keyboard shortens the page
 * but must never turn the panes round.
 */
fun splitSideBySide(
    paneWidth: Dp,
    windowWidth: Dp,
    windowHeight: Dp,
): Boolean = paneWidth >= SideBySideWidth && windowWidth >= windowHeight

/**
 * Below this page height, stacked Split would leave each pane a few lines
 * (a 300 dp split-screen window): the Code pane keeps the page and Preview
 * folds into its one-line strip (edge-cases "Tiny split-screen window").
 */
val MinStackedSplitHeight = 280.dp

private val DividerTouch = 48.dp
internal val DividerHandle = 14.dp
private val HandleLength = 36.dp
private const val MIN_FRACTION = 0.2f
private const val MAX_FRACTION = 0.8f
private const val STEP_FRACTION = 0.1f
private const val EVEN_SPLIT = 0.5f

/** Split's pane area, which clips what it moves out of view: tests measure what is on screen against it. */
const val SPLIT_PANES_TAG = "split-panes"

/** How long the edited pane takes to fill the page, and to give the room back (ux-spec "Split while typing"). */
private const val SINGLE_PANE_MILLIS = 160

/**
 * The page area in Code, Split or Preview. The Code pane and the Preview
 * pane are each hosted with `movableContentOf`, so switching modes or
 * rotating moves the **same** `MarkdownCodeView` and WebView instead of
 * rebuilding them (robustness §5a, rule 2): cursor, selection, scroll,
 * undo and the keyboard connection survive.
 *
 * In Split, both panes stay in the composition, at the same place in it,
 * whatever Split shows: a pane that is hidden is only moved out of view.
 * Taking a view out of the composition, or moving it to another parent,
 * detaches it from the window, and a focused view that leaves the window
 * takes the keyboard with it (edge-cases "Split: the keyboard showed and
 * went away").
 *
 * @param codePane draws the Code pane into the modifier it is given.
 * @param previewPane draws the Preview pane; see [PreviewHostState].
 * @param splitTyping Settings → When typing in Split: while [EditorUiState.typingPane] is set, stacked Split
 *   shows only that pane ([SplitTyping.EditedPane]) or keeps both.
 */
@Composable
fun EditorPanes(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    codePane: @Composable (Modifier) -> Unit,
    previewPane: @Composable (PreviewHostState) -> Unit,
    previewState: PreviewHostState,
    splitTyping: SplitTyping,
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
    val window = LocalWindowInfo.current.containerSize
    val (windowWidth, windowHeight) = with(LocalDensity.current) { window.width.toDp() to window.height.toDp() }
    BoxWithConstraints(modifier.fillMaxSize().background(SS.colors.bg)) {
        val sideBySide = splitSideBySide(maxWidth, windowWidth, windowHeight)
        val tooShortToStack = !sideBySide && maxHeight < MinStackedSplitHeight
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
                val show = splitShow(state.typingPane, splitTyping, sideBySide, tooShortToStack)
                Split(state, onAction, code, preview, previewState, sideBySide, show)
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
    Box(modifier.reportTouches(remember(onAction) { { onAction(EditorAction.PaneTouched(Pane.Code)) } })) {
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
    show: SplitShow,
) {
    // The divider position is remembered separately for each orientation (ux-spec "Layouts").
    var fraction by rememberSaveable(sideBySide) { mutableFloatStateOf(EVEN_SPLIT) }
    val setFraction: (Float) -> Unit = { fraction = it.coerceIn(MIN_FRACTION, MAX_FRACTION) }
    val filling =
        when (show) {
            SplitShow.CodeOnly -> Pane.Code
            SplitShow.PreviewOnly -> Pane.Preview
            else -> null
        }
    // The pane that filled the page last, so it gives the room back from the side it took it.
    val last = remember { FillingPane() }
    if (filling != null) last.pane = filling
    val progress =
        animateFloatAsState(
            if (filling != null) 1f else 0f,
            tween(SINGLE_PANE_MILLIS),
            label = "splitTyping",
        )
    val codeHidden = show == SplitShow.PreviewOnly
    val previewHidden = show == SplitShow.CodeOnly || show == SplitShow.CodeAndStrip
    val orientation = if (sideBySide) Orientation.Horizontal else Orientation.Vertical
    // Clipped, so a pane moved out of view is neither drawn nor touched.
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag(SPLIT_PANES_TAG)) {
        val total = if (sideBySide) maxWidth else maxHeight
        Layout(
            content = {
                // The order never changes: the same two views stay in the same place in the composition.
                CodeWithChip(
                    state,
                    onAction,
                    code,
                    Modifier.layoutId(SplitPart.Code).hiddenFromAccessibility(codeHidden),
                )
                DividerBand(orientation, Modifier.layoutId(SplitPart.Divider))
                PreviewWithChip(
                    state,
                    onAction,
                    preview,
                    previewState,
                    Modifier.layoutId(SplitPart.Preview).hiddenFromAccessibility(previewHidden),
                )
                if (show == SplitShow.CodeAndStrip) {
                    CollapsedPreviewStrip(state.breadcrumb.lastOrNull(), Modifier.layoutId(SplitPart.Strip))
                }
            },
            measurePolicy = splitMeasurePolicy(sideBySide, fraction, show, last, progress),
        )
        if (show == SplitShow.Both && progress.value == 0f) {
            DividerTouchArea(orientation, total, fraction, setFraction)
        }
    }
}

/** A pane moved out of view is not offered to TalkBack either. */
private fun Modifier.hiddenFromAccessibility(hidden: Boolean): Modifier = if (hidden) clearAndSetSemantics {} else this

/** Reports the first finger on a pane without consuming anything: in Split, that pane now leads. */
private fun Modifier.reportTouches(onTouched: () -> Unit): Modifier =
    pointerInput(onTouched) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onTouched()
        }
    }

/** The visible 14 dp band with its grip. */
@Composable
private fun DividerBand(
    orientation: Orientation,
    modifier: Modifier = Modifier,
) {
    val vertical = orientation == Orientation.Vertical
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(SS.colors.chrome),
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
private fun CollapsedPreviewStrip(
    section: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            modifier
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
