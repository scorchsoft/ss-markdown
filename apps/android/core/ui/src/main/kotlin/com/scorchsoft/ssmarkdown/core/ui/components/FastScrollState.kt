package com.scorchsoft.ssmarkdown.core.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The state behind one pane's fast-scroll thumb (docs/ux-spec.md, "Fast
 * scroll"): where the pane is, whether the thumb shows, and what the
 * section bubble says. The pane writes its position here on every scroll;
 * [FastScrollThumb] reads it in the layout phase only, so a fling moves the
 * thumb without recomposing anything.
 *
 * Inputs: [onScrolled] (a fraction, a visible fraction and the "show"
 * signal), [onTouch] (a finger on the pane), [suppressed] (selection
 * handles or the text toolbar in use) and [setSection]. Output: the drag
 * fractions [FastScrollThumb] hands to its `onDrag`.
 *
 * Every member is for the main thread.
 */
@Stable
class FastScrollState {
    private val position = mutableFloatStateOf(0f)

    /** Where the thumb sits, 0 (top) to 1 (bottom): the pane's position, or the finger's while dragging. */
    val fraction: Float get() = position.floatValue

    /** How much of the document the pane shows at once, 0 to 1. */
    var visibleFraction: Float = 1f
        private set

    /** True while the thumb is drawn and takes touches. */
    var isShown: Boolean by mutableStateOf(false)
        private set

    /** True while a finger holds the thumb. */
    var isDragging: Boolean by mutableStateOf(false)
        private set

    /** The bubble's text while dragging: the section under the pane's top, or null for no bubble. */
    var section: String? by mutableStateOf(null)
        private set

    private val sectionKeyState = mutableIntStateOf(NO_SECTION)

    /** Which section [section] names; a change while dragging is a haptic tick. */
    val sectionKey: Int get() = sectionKeyState.intValue

    /**
     * True while the selection handles or the text toolbar are in use: the
     * thumb hides at once and stays hidden, so it never sits under a handle.
     */
    var suppressed: Boolean = false
        set(value) {
            field = value
            if (value && !isDragging) isShown = false
        }

    /** Whether the document is long enough for a thumb: more than [MIN_SCREENS] screens. */
    val isLongEnough: Boolean get() = visibleFraction * MIN_SCREENS < 1f

    /** A finger is on the pane, or was until its scroll ends: only then does scrolling show the thumb. */
    private var armed = false
    private var touching = false

    /** Milliseconds since the pane last scrolled, counted by the thumb while it shows. */
    private var quietMillis = 0L

    /** Where the thumb's touch box was last placed, px from the track's top; the drag reads it. */
    internal var placedTop: Float = 0f

    /**
     * The pane scrolled to [fraction], showing [visibleFraction] of the
     * document. Shows the thumb when the user is the one scrolling (a
     * finger went down on the pane: [onTouch]), so a pane following its
     * twin in Split, or a jump from the Navigator, stays calm.
     */
    fun onScrolled(
        fraction: Float,
        visibleFraction: Float,
    ) {
        this.visibleFraction = visibleFraction.coerceIn(0f, 1f)
        if (!isDragging) position.floatValue = fraction.coerceIn(0f, 1f)
        quietMillis = 0L
        when {
            isDragging -> Unit
            !isLongEnough -> isShown = false
            armed && !suppressed -> isShown = true
        }
    }

    /** Updates the position and size without the "show" signal: an edit, a resize, a page that grew. */
    fun onMeasured(
        fraction: Float,
        visibleFraction: Float,
    ) {
        this.visibleFraction = visibleFraction.coerceIn(0f, 1f)
        if (!isDragging) position.floatValue = fraction.coerceIn(0f, 1f)
        if (!isLongEnough && !isDragging) isShown = false
    }

    /** A finger went down on the pane ([down]) or the last one lifted. */
    fun onTouch(down: Boolean) {
        touching = down
        if (down) armed = true
    }

    /**
     * The section under the pane's top is [key], read as [label] (null: no
     * bubble). The host calls it as the pane moves; only a change is stored.
     */
    fun setSection(
        key: Int,
        label: String?,
    ) {
        if (key != sectionKeyState.intValue) sectionKeyState.intValue = key
        if (label != section) section = label
    }

    /** [elapsedMillis] passed with the thumb shown: hides it [HIDE_DELAY_MILLIS] after the last scroll. */
    internal fun tick(elapsedMillis: Long) {
        quietMillis += elapsedMillis
        if (isShown && !isDragging && quietMillis >= HIDE_DELAY_MILLIS) hide()
    }

    /** Hides the thumb now; scrolling shows it again only after the next touch. */
    fun hide() {
        isShown = false
        armed = touching
    }

    internal fun startDrag() {
        isDragging = true
        quietMillis = 0L
    }

    internal fun dragTo(fraction: Float) {
        position.floatValue = fraction.coerceIn(0f, 1f)
    }

    internal fun endDrag() {
        isDragging = false
        quietMillis = 0L
    }

    companion object {
        /** The thumb fades this long after scrolling stops (unless held). */
        const val HIDE_DELAY_MILLIS: Long = 1_500L

        /** A document must be longer than this many screens to get a thumb. */
        const val MIN_SCREENS: Float = 4f

        /** [sectionKey] before the first heading, or in a document with none. */
        const val NO_SECTION: Int = -1
    }
}
