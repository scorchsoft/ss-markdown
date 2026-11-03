package com.scorchsoft.ssmarkdown.core.ui.components

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SS
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Sizes and timings of the fast-scroll thumb (docs/ux-spec.md, "Fast scroll"). */
object FastScrollDefaults {
    /** The pill at rest, and while a finger holds it. */
    val PillWidth = 6.dp
    val PillWidthHeld = 10.dp
    val PillHeight = 44.dp

    /** The touch target around the pill: the platform's 48 dp minimum, and well inside the 200 dp exclusion cap. */
    val Touch = 48.dp

    /** Gap between the pill and the pane's right edge. */
    val EdgeGap = 3.dp

    /** Gap between the bubble and the touch target. */
    val BubbleGap = 4.dp

    /** The bubble never takes more than this of the pane's width. */
    const val BUBBLE_MAX_WIDTH_FRACTION = 0.7f

    /** How often a shown thumb checks whether scrolling has stopped. */
    const val TICK_MILLIS = 100L

    /** The fade in and out, when animations are on. */
    const val FADE_MILLIS = 150

    /** Test tags: the touch target and the bubble. */
    const val THUMB_TAG = "fastScrollThumb"
    const val BUBBLE_TAG = "fastScrollBubble"
}

/**
 * The fast-scroll thumb, laid over a pane (fill the pane with [modifier]):
 * a slim pill at the right edge that appears when the user scrolls a long
 * document and fades [FastScrollState.HIDE_DELAY_MILLIS] after scrolling
 * stops. Dragging it hands [onDrag] the fraction of the document the pill
 * is at, live; while held, a bubble beside it shows
 * [FastScrollState.section] and a light haptic tick marks each change of
 * section (the system setting decides whether it is felt).
 *
 * - It takes touches only while shown, so a tap on the faded edge reaches
 *   the text, and only in its 48 dp target, never a whole edge strip.
 * - While shown (and not held: the system decides at touch-down), its
 *   target is excluded from the system back gesture.
 * - It is hidden from TalkBack: it is a pointer convenience, and TalkBack
 *   users have the Navigator and page scrolling.
 * - With [animate] false ("Remove animations") it shows and hides without
 *   a fade.
 *
 * The position is read in the layout phase, so a fling or drag moves the
 * pill without recomposing it, and the drag allocates nothing per frame.
 */
@Composable
fun FastScrollThumb(
    state: FastScrollState,
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    animate: Boolean = animationsEnabled(),
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state) {
        // A change of section while held is felt; the first value (and any while not held) is not.
        snapshotFlow { state.sectionKey }.collect {
            if (state.isDragging) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    val shown = state.isShown
    val held = state.isDragging
    // Counts quiet time only while shown and not held, so nothing runs while it is hidden or dragged.
    LaunchedEffect(state, shown, held) {
        while (shown && !held && state.isShown) {
            delay(FastScrollDefaults.TICK_MILLIS)
            state.tick(FastScrollDefaults.TICK_MILLIS)
        }
    }
    val fade =
        animateFloatAsState(if (shown) 1f else 0f, if (animate) tween(FastScrollDefaults.FADE_MILLIS) else snap())
    // "Remove animations": on and off on the frame it changes, never a frame of fade.
    val alpha =
        if (animate) {
            fade.value
        } else if (shown) {
            1f
        } else {
            0f
        }
    if (!shown && alpha == 0f) return
    val drag = rememberUpdatedState(onDrag)
    val start = rememberUpdatedState(onDragStart)
    val end = rememberUpdatedState(onDragEnd)
    BoxWithConstraints(modifier.fillMaxSize().semantics { hideFromAccessibility() }) {
        val density = LocalDensity.current
        val touchPx = with(density) { FastScrollDefaults.Touch.toPx() }
        val track = (constraints.maxHeight - touchPx).coerceAtLeast(1f)
        val placeAt: () -> Int = remember(state, track) { { (state.fraction * track).roundToInt() } }
        if (state.isDragging) {
            state.section?.let { label ->
                Bubble(label, placeAt, Modifier.widthIn(max = maxWidth * FastScrollDefaults.BUBBLE_MAX_WIDTH_FRACTION))
            }
        }
        val input =
            if (!shown) {
                Modifier
            } else {
                Modifier
                    .then(if (state.isDragging) Modifier else Modifier.systemGestureExclusion())
                    .pointerInput(state, track) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            down.consume()
                            // Window-independent finger y: the target moves under the finger as it drags.
                            val grabbedAt = state.placedTop + down.position.y
                            val grabbedTop = state.placedTop
                            state.startDrag()
                            start.value()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes[0]
                                if (change.pressed) {
                                    val fingerY = state.placedTop + change.position.y
                                    val fraction = ((grabbedTop + fingerY - grabbedAt) / track).coerceIn(0f, 1f)
                                    if (fraction != state.fraction) {
                                        state.dragTo(fraction)
                                        drag.value(fraction)
                                    }
                                }
                                change.consume()
                            } while (change.pressed)
                            state.endDrag()
                            end.value()
                        }
                    }
            }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val top = placeAt()
                    state.placedTop = top.toFloat()
                    IntOffset(0, top)
                }.size(FastScrollDefaults.Touch)
                .testTag(FastScrollDefaults.THUMB_TAG)
                .then(input)
                .graphicsLayer { this.alpha = if (animate) fade.value else 1f }
                .pill(state),
        )
    }
}

/** Draws the pill at the target's right edge, wider while held. */
@Composable
private fun Modifier.pill(state: FastScrollState): Modifier {
    val color = SS.colors.gutter
    val held = SS.colors.inkMuted
    val density = LocalDensity.current
    val rest = with(density) { FastScrollDefaults.PillWidth.toPx() }
    val wide = with(density) { FastScrollDefaults.PillWidthHeld.toPx() }
    val tall = with(density) { FastScrollDefaults.PillHeight.toPx() }
    val gap = with(density) { FastScrollDefaults.EdgeGap.toPx() }
    return drawBehind {
        val width = if (state.isDragging) wide else rest
        drawRoundRect(
            color = if (state.isDragging) held else color,
            topLeft = Offset(size.width - gap - width, (size.height - tall) / 2),
            size = Size(width, tall),
            cornerRadius = CornerRadius(width / 2),
        )
    }
}

/** The section bubble, level with the pill and just left of its target. */
@Composable
private fun Bubble(
    label: String,
    placeAt: () -> Int,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val density = LocalDensity.current
    val inset = with(density) { (FastScrollDefaults.Touch + FastScrollDefaults.BubbleGap).roundToPx() }
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(-inset, placeAt()) }
                .testTag(FastScrollDefaults.BUBBLE_TAG)
                .background(SS.colors.chrome, shape)
                .border(SS.dimens.hairline, SS.colors.inputOutline, shape)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text(
                label,
                style = SS.type.bodyStrong,
                color = SS.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Reports fingers on a pane to [state] without consuming anything: only a
 * scroll that follows a touch shows the thumb, so a pane moved by its twin
 * in Split or by a Navigator jump stays calm.
 */
fun Modifier.fastScrollTouches(state: FastScrollState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.onTouch(true)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            state.onTouch(false)
        }
    }

/** False when the system's "Remove animations" (animator duration scale 0) is on. */
@Composable
fun animationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
