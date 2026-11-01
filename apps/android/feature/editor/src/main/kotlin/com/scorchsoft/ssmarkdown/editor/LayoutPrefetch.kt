package com.scorchsoft.ssmarkdown.editor

/**
 * Lays out up to [budget] lines of the render [margin] that are not cached
 * yet, nearest to the [visible] lines first and on the side the scroll is
 * heading, so a fling finds them ready (robustness.md: the render margin
 * leans towards the scroll direction). Cached lines cost a map lookup.
 */
internal fun EditorGeometry.prefetch(
    visible: IntRange,
    margin: IntRange,
    direction: ScrollDirection,
    budget: Int,
) {
    val below = (visible.last + 1)..margin.last
    val above = (visible.first - 1) downTo margin.first
    val order = if (direction == ScrollDirection.TOWARDS_START) above + below else below + above
    var left = budget
    for (line in order) {
        if (left == 0) break
        if (!isLaidOut(line)) {
            layoutFor(line)
            left--
        }
    }
}
