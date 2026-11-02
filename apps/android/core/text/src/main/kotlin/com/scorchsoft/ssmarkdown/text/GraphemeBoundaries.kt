package com.scorchsoft.ssmarkdown.text

import java.text.BreakIterator

/**
 * Finds grapheme cluster boundaries — what a person sees as one character
 * (an emoji with skin tone, a flag, `e` + combining accent) — so cursor
 * movement and Backspace never split one (docs/edge-cases.md, "Text and
 * input").
 *
 * Works on a small window around the offset and widens it only when a
 * cluster reaches the window's edge, so a call costs the same on a 100 MB
 * file as on a tweet. Uses `java.text.BreakIterator`, which is ICU-backed
 * on Android and implements extended grapheme clusters on JDK 20+.
 *
 * Holds a [BreakIterator], so it is not thread-safe: one per editor.
 */
public class GraphemeBoundaries(
    private val iterator: BreakIterator = BreakIterator.getCharacterInstance(),
) {
    /** The first boundary after [offset], or [CharSequence.length] at the end. */
    public fun next(
        text: CharSequence,
        offset: Int,
    ): Int {
        checkOffset(text, offset)
        if (offset == text.length) return offset
        var radius = INITIAL_WINDOW
        while (true) {
            val start = maxOf(0, offset - CONTEXT)
            val end = minOf(text.length, offset + radius)
            iterator.setText(text.subSequence(start, end).toString())
            val found = iterator.following(offset - start) + start
            // A boundary at the window's edge may be the edge, not a boundary.
            if (found < end || end == text.length) return found
            radius *= 2
        }
    }

    /** The last boundary before [offset], or 0 at the start. */
    public fun previous(
        text: CharSequence,
        offset: Int,
    ): Int {
        checkOffset(text, offset)
        if (offset == 0) return 0
        var radius = INITIAL_WINDOW
        while (true) {
            val start = maxOf(0, offset - radius)
            val end = minOf(text.length, offset + CONTEXT)
            iterator.setText(text.subSequence(start, end).toString())
            val found = iterator.preceding(offset - start) + start
            if (found > start || start == 0) return found
            radius *= 2
        }
    }

    /** True when [offset] does not fall inside a cluster. */
    public fun isBoundary(
        text: CharSequence,
        offset: Int,
    ): Boolean {
        checkOffset(text, offset)
        return offset == 0 || offset == text.length || next(text, previous(text, offset)) == offset
    }

    private fun checkOffset(
        text: CharSequence,
        offset: Int,
    ) {
        if (offset < 0 || offset > text.length) throw IndexOutOfBoundsException("offset $offset, length ${text.length}")
    }

    private companion object {
        /** Chars read on the far side of the offset, for rules that look back (or ahead) a little. */
        const val CONTEXT = 32

        /** Enough for any everyday cluster; Zalgo text just widens the window. */
        const val INITIAL_WINDOW = 64
    }
}
