package com.scorchsoft.ssmarkdown.core.ui.components

import androidx.compose.ui.unit.dp

/** Sizes and timings of the fast-scroll thumb (docs/ux-spec.md, "Fast scroll"). */
object FastScrollDefaults {
    /** The pill at rest, and while a finger holds it. */
    val PillWidth = 6.dp
    val PillWidthHeld = 10.dp
    val PillHeight = 44.dp

    /** The touch target around the pill: the platform's 48 dp minimum, and well inside the 200 dp exclusion cap. */
    val Touch = 48.dp

    /** The ring of page colour around the pill, so it stays distinct over text. */
    val HaloWidth = 1.5.dp

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
