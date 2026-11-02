package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The width of each key in the strip's scrolling area, chosen so the area
 * runs right up to the pinned keys and its last visible key is cut at
 * about half (docs/ux-spec.md, "Strip discoverability"). A key cut at the
 * very edge, or not cut at all, hides that the row scrolls; half a key
 * says "there is more" without a hint or a chevron.
 *
 * The room left over after whole keys and half a key is shared out among
 * the scrolling keys, so they grow a little past 48 dp instead of leaving
 * a gap before the pinned group (Andrew, 2026-11-02: the gap made the
 * pinned group look too wide). When every key fits, keys stay 48 dp and
 * nothing is cut.
 */
internal object PeekLayout {
    /** How much of the last visible key shows, when the keys have to grow to get there. */
    const val PEEK_FRACTION = 0.5f

    /** "About half": a cut in this band keeps the keys at exactly [key] wide. */
    private const val MIN_PEEK = 0.35f
    private const val MAX_PEEK = 0.65f

    fun keyWidth(
        available: Dp,
        key: Dp,
        keyCount: Int,
        padding: Dp = 0.dp,
    ): Dp {
        val room = available - padding
        if (key * keyCount <= room) return key
        val keys = room / key
        if (keys - keys.toInt() in MIN_PEEK..MAX_PEEK) return key
        // Fewer whole keys, never narrower ones: 48 dp is the touch target.
        val whole = (keys - PEEK_FRACTION).toInt().coerceAtLeast(0)
        return if (whole == 0) key else room / (whole + PEEK_FRACTION)
    }

    /** The scrolling area's width: all the room there is, or just its keys when they all fit. */
    fun viewportWidth(
        available: Dp,
        key: Dp,
        keyCount: Int,
        padding: Dp = 0.dp,
    ): Dp {
        val content = key * keyCount + padding
        return if (content <= available) content else available
    }
}
