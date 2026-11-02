package com.scorchsoft.ssmarkdown.markdown

/**
 * The [IncrementalTokenizer]'s per-line bookkeeping, one slot per line:
 * the state a line was tokenised from, the state it ended in, the
 * [LineRole] it was read with and the role it asked of the line above.
 *
 * Stored as a **gap buffer** (performance review PERF-E). Plain arrays
 * made every line break cost a shift of the whole tail: Enter near the
 * top of a 1.4M-line file moved four 1.4M-entry arrays, about 1 ms per
 * tokenizer on the host and several on a phone. Here the unused capacity
 * sits at the last edit, so the next line break there moves nothing.
 * The gap only travels when the edits do: moving it costs the distance
 * between two edits, once, and a run of line breaks in one place (typing
 * a list, pasting line by line, Enter Enter Enter) costs O(1) each. A
 * change inside lines (the common keystroke) never moves it at all.
 *
 * Reads are one comparison more than a flat array, which the accurate
 * pass does not notice.
 */
@Suppress("TooManyFunctions") // Four per-line fields, each read and written, plus the gap's own moves.
internal class LineStates(
    lineCount: Int,
) {
    private var starts = arrayOfNulls<LineState>(lineCount + INITIAL_GAP)
    private var ends = arrayOfNulls<LineState>(lineCount + INITIAL_GAP)
    private var roles = ByteArray(lineCount + INITIAL_GAP)
    private var aboveRoles = ByteArray(lineCount + INITIAL_GAP)

    /** Slots `[gapStart, gapStart + gapLength)` hold no line. */
    private var gapStart = lineCount
    private var gapLength = INITIAL_GAP

    /** Lines held. */
    var size: Int = lineCount
        private set

    /** Slots moved to follow edits so far: the PERF-E regression statistic. */
    var movedEntries: Long = 0
        private set

    /** Slots allocated, lines and gap; for the memory estimate. */
    val capacity: Int get() = starts.size

    fun start(line: Int): LineState? = starts[slot(line)]

    fun end(line: Int): LineState? = ends[slot(line)]

    fun role(line: Int): Byte = roles[slot(line)]

    fun aboveRole(line: Int): Byte = aboveRoles[slot(line)]

    fun setRole(
        line: Int,
        role: Byte,
    ) {
        roles[slot(line)] = role
    }

    /** Records what tokenising [line] produced. */
    fun store(
        line: Int,
        start: LineState,
        end: LineState,
        role: Byte,
        aboveRole: Byte,
    ) {
        val at = slot(line)
        starts[at] = start
        ends[at] = end
        roles[at] = role
        aboveRoles[at] = aboveRole
    }

    /**
     * Lines `[from, from + removed)` became [inserted] fresh lines (no
     * states, [LineRole.AS_IS]). Later lines keep their states and move
     * down or up.
     */
    fun replace(
        from: Int,
        removed: Int,
        inserted: Int,
    ) {
        if (removed == inserted) {
            // A change within lines, the common keystroke: reset in place, move nothing.
            for (line in from until from + removed) clear(slot(line))
            return
        }
        moveGapTo(from + removed)
        // The removed lines join the gap; drop their references so they can be collected.
        for (at in from until from + removed) clear(at)
        gapStart = from
        gapLength += removed
        size -= removed
        if (inserted > gapLength) grow(inserted)
        for (at in gapStart until gapStart + inserted) clear(at)
        gapStart += inserted
        gapLength -= inserted
        size += inserted
    }

    private fun slot(line: Int): Int = if (line < gapStart) line else line + gapLength

    private fun clear(at: Int) {
        starts[at] = null
        ends[at] = null
        roles[at] = 0
        aboveRoles[at] = 0
    }

    /** Moves the gap so it starts at line [line]; costs the distance moved. */
    private fun moveGapTo(line: Int) {
        if (line == gapStart) return
        if (line < gapStart) {
            // Lines [line, gapStart) move to just after the gap.
            val count = gapStart - line
            copy(line, line + gapLength, count)
            for (at in line until minOf(line + gapLength, line + count)) clear(at)
            movedEntries += count
        } else {
            // Lines [gapStart, line) sit after the gap; move them before it.
            val count = line - gapStart
            val from = gapStart + gapLength
            copy(from, gapStart, count)
            for (at in maxOf(gapStart + count, from) until from + count) clear(at)
            movedEntries += count
        }
        gapStart = line
    }

    /** Widens the gap by an eighth of the lines (at least [needed] slots); the copy keeps it where it is. */
    private fun grow(needed: Int) {
        val newGap = maxOf(needed, size / GROWTH_DIVISOR, INITIAL_GAP)
        val newCapacity = size + newGap
        val tail = starts.size - gapStart - gapLength
        val newStarts = arrayOfNulls<LineState>(newCapacity)
        val newEnds = arrayOfNulls<LineState>(newCapacity)
        val newRoles = ByteArray(newCapacity)
        val newAbove = ByteArray(newCapacity)
        val oldTail = gapStart + gapLength
        val newTail = gapStart + newGap
        System.arraycopy(starts, 0, newStarts, 0, gapStart)
        System.arraycopy(ends, 0, newEnds, 0, gapStart)
        System.arraycopy(roles, 0, newRoles, 0, gapStart)
        System.arraycopy(aboveRoles, 0, newAbove, 0, gapStart)
        System.arraycopy(starts, oldTail, newStarts, newTail, tail)
        System.arraycopy(ends, oldTail, newEnds, newTail, tail)
        System.arraycopy(roles, oldTail, newRoles, newTail, tail)
        System.arraycopy(aboveRoles, oldTail, newAbove, newTail, tail)
        starts = newStarts
        ends = newEnds
        roles = newRoles
        aboveRoles = newAbove
        gapLength = newGap
    }

    private fun copy(
        from: Int,
        to: Int,
        count: Int,
    ) {
        System.arraycopy(starts, from, starts, to, count)
        System.arraycopy(ends, from, ends, to, count)
        System.arraycopy(roles, from, roles, to, count)
        System.arraycopy(aboveRoles, from, aboveRoles, to, count)
    }

    private companion object {
        /** Room for a few hundred line breaks before the first reallocation. */
        const val INITIAL_GAP = 256

        /** A wider gap costs memory on every line; an eighth amortises the copy without much of it. */
        const val GROWTH_DIVISOR = 8
    }
}
