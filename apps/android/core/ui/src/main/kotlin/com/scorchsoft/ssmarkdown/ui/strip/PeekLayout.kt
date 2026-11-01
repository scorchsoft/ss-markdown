package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min

/**
 * The width of the strip's scrolling area, chosen so the last visible key
 * is cut at about half (docs/ux-spec.md, "Strip discoverability"). A key
 * cut at the very edge, or not cut at all, hides that the row scrolls;
 * half a key says "there is more" without a hint or a chevron.
 *
 * When every key fits, the area is just as wide as its keys and nothing
 * is cut. The leftover width sits between the area and the pinned keys.
 */
internal object PeekLayout {
    /** How much of the last visible key shows. */
    const val PEEK_FRACTION = 0.5f

    fun viewportWidth(
        available: Dp,
        key: Dp,
        keyCount: Int,
        padding: Dp = 0.dp,
    ): Dp {
        val content = key * keyCount + padding
        if (content <= available) return content
        val peek = key * PEEK_FRACTION
        val whole = ((available - padding - peek) / key).toInt().coerceAtLeast(0)
        return min(available, max(peek, key * whole + padding + peek))
    }
}
