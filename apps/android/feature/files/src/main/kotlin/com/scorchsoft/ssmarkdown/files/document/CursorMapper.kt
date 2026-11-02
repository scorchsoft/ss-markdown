package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.merge.LineDiff
import com.scorchsoft.ssmarkdown.files.merge.Lines

/**
 * Maps a cursor or scroll offset from an old text to a new one, so a
 * silent reload keeps you near the same place (robustness §2a).
 */
fun interface CursorMapper {
    /** Where [offset] in [old] lands in [new]. */
    fun map(
        old: CharSequence,
        new: CharSequence,
        offset: Int,
    ): Int

    /** The default. */
    companion object {
        /**
         * Line-diff mapping: a cursor on a line that survived keeps its
         * column on that line; one on a changed line goes to the start of
         * the nearest surviving line above it.
         */
        val BY_LINE: CursorMapper = CursorMapper(::mapByLine)

        @Suppress("ReturnCount") // End of text and "no surviving line above" are the two edge answers.
        private fun mapByLine(
            old: CharSequence,
            new: CharSequence,
            offset: Int,
        ): Int {
            if (offset >= old.length) return new.length
            val oldLines = Lines.split(old)
            val newLines = Lines.split(new)
            val (a, b) = Lines.intern(oldLines, newLines)
            val partner = LineDiff().matches(a, b)
            var start = 0
            var line = 0
            while (start + oldLines[line].length <= offset) start += oldLines[line++].length
            val column = offset - start
            var anchor = line
            while (anchor >= 0 && partner[anchor] < 0) anchor--
            if (anchor < 0) return 0
            val newStart = newLines.take(partner[anchor]).sumOf { it.length }
            val keepColumn = if (anchor == line) column.coerceAtMost(newLines[partner[anchor]].length) else 0
            return (newStart + keepColumn).coerceIn(0, new.length)
        }
    }
}
