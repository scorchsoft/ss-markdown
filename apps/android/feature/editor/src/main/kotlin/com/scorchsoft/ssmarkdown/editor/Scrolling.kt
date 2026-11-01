package com.scorchsoft.ssmarkdown.editor

// Viewport maths over a HeightMap, kept free of Android so it is tested
// on its own. `scrollY` is the distance from the top of line 0 to the top
// of the viewport, in px.

/** The largest valid scrollY: the last line sits at the bottom of the viewport. */
fun HeightMap.maxScrollY(viewportHeight: Int): Int =
    (contentHeight - viewportHeight).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

/**
 * Lines at least partly inside the viewport. Never empty: a zero-height
 * view still "shows" the line at its top, so the caret maths always has a
 * line to work with.
 */
fun HeightMap.visibleLines(
    scrollY: Int,
    viewportHeight: Int,
): IntRange {
    val first = lineAt(scrollY.toLong())
    val last = lineAt(scrollY.toLong() + (viewportHeight - 1).coerceAtLeast(0))
    return first..last
}

/**
 * The visible lines plus a margin of about one screen, leaning towards
 * [direction]: a full screen ahead and a quarter behind while scrolling,
 * half a screen each side at rest. These are the lines worth having laid
 * out; only [visibleLines] are drawn.
 */
fun HeightMap.renderLines(
    scrollY: Int,
    viewportHeight: Int,
    direction: ScrollDirection,
): IntRange {
    val (above, below) =
        when (direction) {
            ScrollDirection.TOWARDS_END -> viewportHeight / BEHIND_FRACTION to viewportHeight
            ScrollDirection.TOWARDS_START -> viewportHeight to viewportHeight / BEHIND_FRACTION
            ScrollDirection.IDLE -> viewportHeight / 2 to viewportHeight / 2
        }
    val top = (scrollY.toLong() - above).coerceAtLeast(0)
    val bottom = (scrollY.toLong() + viewportHeight + below - 1).coerceAtLeast(0)
    return lineAt(top)..lineAt(bottom)
}

/**
 * The scrollY that brings content `[top, bottom)` fully into view with the
 * least movement, or [scrollY] unchanged when it is already visible.
 */
fun revealScroll(
    top: Long,
    bottom: Long,
    scrollY: Int,
    viewportHeight: Int,
): Int =
    when {
        top < scrollY -> top.toInt()
        bottom > scrollY.toLong() + viewportHeight -> (bottom - viewportHeight).coerceAtLeast(0).toInt()
        else -> scrollY
    }

/** While scrolling, the margin behind is a quarter of the one ahead. */
private const val BEHIND_FRACTION = 4
