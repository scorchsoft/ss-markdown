package com.scorchsoft.ssmarkdown.editor

import java.text.BreakIterator

/**
 * Word boundaries for double-tap and long-press selection and for
 * Ctrl+arrow movement, found with the platform word `BreakIterator` so
 * CJK, Thai and accented text split where a reader expects.
 *
 * Works on a window of at most [WINDOW] chars either side of the offset,
 * so a long-press inside a 5 MB line of base64 costs the same as one in a
 * sentence; a "word" longer than the window is cut at its edge, which is
 * the right answer for selecting part of a megabyte token anyway.
 *
 * Holds a [BreakIterator], so it is not thread-safe: one per editor.
 */
class WordFinder(
    private val iterator: BreakIterator = BreakIterator.getWordInstance(),
) {
    /**
     * The word at or just before [offset] as `start until end`; a tap on
     * whitespace or punctuation gets the run of that same character
     * instead (so a double-tap on `**` selects the marker). Empty only at
     * the end of an empty line.
     */
    fun wordAt(
        text: CharSequence,
        offset: Int,
    ): IntRange {
        val at = offset.coerceIn(0, text.length)
        val windowStart = maxOf(0, at - WINDOW)
        val windowEnd = minOf(text.length, at + WINDOW)
        val window = text.subSequence(windowStart, windowEnd).toString()
        val local = at - windowStart
        val probe =
            when {
                local < window.length && isWordChar(window[local]) -> local
                local > 0 && isWordChar(window[local - 1]) -> local - 1
                else -> return sameCharRun(window, local, windowStart)
            }
        iterator.setText(window)
        val end = iterator.following(probe)
        val start = iterator.preceding(end)
        return (windowStart + start) until (windowStart + end)
    }

    /** The start of the next word after [offset] (Ctrl+Right), or the end of [text]. */
    fun nextWordEnd(
        text: CharSequence,
        offset: Int,
    ): Int {
        var i = offset.coerceIn(0, text.length)
        val limit = minOf(text.length, i + WINDOW)
        while (i < limit && !isWordChar(text[i])) i++
        while (i < limit && isWordChar(text[i])) i++
        return i
    }

    /** The start of the word before [offset] (Ctrl+Left), or 0. */
    fun previousWordStart(
        text: CharSequence,
        offset: Int,
    ): Int {
        var i = offset.coerceIn(0, text.length)
        val limit = maxOf(0, i - WINDOW)
        while (i > limit && !isWordChar(text[i - 1])) i--
        while (i > limit && isWordChar(text[i - 1])) i--
        return i
    }

    private fun sameCharRun(
        window: String,
        local: Int,
        windowStart: Int,
    ): IntRange {
        val index = if (local < window.length && window[local] != '\n') local else local - 1
        if (index < 0 || window[index] == '\n') return windowStart + local until windowStart + local
        val c = window[index]
        var start = index
        var end = index + 1
        while (start > 0 && window[start - 1] == c) start--
        while (end < window.length && window[end] == c) end++
        return (windowStart + start) until (windowStart + end)
    }

    private companion object {
        /** Chars read either side of the offset. */
        const val WINDOW = 256

        fun isWordChar(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_' || c.isSurrogate()
    }
}
