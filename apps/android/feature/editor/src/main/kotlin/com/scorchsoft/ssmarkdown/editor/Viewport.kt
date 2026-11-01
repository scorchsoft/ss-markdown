package com.scorchsoft.ssmarkdown.editor

/**
 * Which way the content last moved. The render margin leans the same way,
 * so the lines a fling is about to reveal are laid out before they arrive
 * (docs/robustness.md, "Editor view").
 */
enum class ScrollDirection {
    /** Scrolling back towards line 0. */
    TOWARDS_START,

    /** Scrolling on towards the last line. */
    TOWARDS_END,

    /** Not scrolling: the margin is split evenly. */
    IDLE,
}

/**
 * The vertical layout maths of the code view, kept free of Android so it is
 * tested on its own. With a fixed line height (No wrap mode) every question
 * here is arithmetic, which is what makes a 2M-line document cost the same
 * per frame as a 20-line one.
 *
 * All values are in pixels; `scrollY` is the distance from the top of line 0
 * to the top of the viewport.
 */
class Viewport(
    /** Height of every line, in px. Positive. */
    val lineHeight: Int,
) {
    init {
        require(lineHeight > 0) { "lineHeight must be positive, was $lineHeight" }
    }

    /** Total content height for [lineCount] lines. Long, because 100M lines would overflow Int. */
    fun contentHeight(lineCount: Int): Long = lineCount.toLong() * lineHeight

    /** The largest valid scrollY: the last line sits at the bottom of the viewport. */
    fun maxScrollY(
        lineCount: Int,
        viewportHeight: Int,
    ): Int = (contentHeight(lineCount) - viewportHeight).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    /** Top of [line] in content coordinates. */
    fun lineTop(line: Int): Long = line.toLong() * lineHeight

    /** The line under content-space [y], clamped to the document. */
    fun lineAt(
        y: Long,
        lineCount: Int,
    ): Int = (y / lineHeight).coerceIn(0, (lineCount - 1).coerceAtLeast(0).toLong()).toInt()

    /**
     * Lines at least partly inside the viewport. Never empty for a non-empty
     * document: a zero-height view still "shows" the line at its top, so the
     * caret maths always has a line to work with.
     */
    fun visibleLines(
        scrollY: Int,
        viewportHeight: Int,
        lineCount: Int,
    ): IntRange {
        if (lineCount <= 0) return IntRange.EMPTY
        val first = lineAt(scrollY.toLong(), lineCount)
        val last = lineAt(scrollY.toLong() + (viewportHeight - 1).coerceAtLeast(0), lineCount)
        return first..last
    }

    /**
     * The visible lines plus a margin of about one screen, leaning towards
     * [direction]: a full screen ahead and a quarter behind while scrolling,
     * half a screen each side at rest. These are the lines worth having laid
     * out; only [visibleLines] are drawn.
     */
    fun renderLines(
        scrollY: Int,
        viewportHeight: Int,
        lineCount: Int,
        direction: ScrollDirection,
    ): IntRange {
        if (lineCount <= 0) return IntRange.EMPTY
        val (above, below) =
            when (direction) {
                ScrollDirection.TOWARDS_END -> viewportHeight / BEHIND_FRACTION to viewportHeight
                ScrollDirection.TOWARDS_START -> viewportHeight to viewportHeight / BEHIND_FRACTION
                ScrollDirection.IDLE -> viewportHeight / 2 to viewportHeight / 2
            }
        val top = scrollY.toLong() - above
        val bottom = scrollY.toLong() + viewportHeight + below - 1
        return lineAt(top.coerceAtLeast(0), lineCount)..lineAt(bottom.coerceAtLeast(0), lineCount)
    }

    /**
     * The scrollY that brings [line] fully into view with the least movement,
     * or [scrollY] unchanged when it is already visible.
     */
    fun scrollToReveal(
        line: Int,
        scrollY: Int,
        viewportHeight: Int,
    ): Int {
        val top = lineTop(line)
        val bottom = top + lineHeight
        return when {
            top < scrollY -> top.toInt()
            bottom > scrollY.toLong() + viewportHeight -> (bottom - viewportHeight).coerceAtLeast(0).toInt()
            else -> scrollY
        }
    }

    private companion object {
        /** While scrolling, the margin behind is a quarter of the one ahead. */
        const val BEHIND_FRACTION = 4
    }
}
