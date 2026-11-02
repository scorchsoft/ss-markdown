package com.scorchsoft.ssmarkdown.editor

/**
 * Find & replace matches as the Code pane draws them (docs/ux-spec.md,
 * "Find & replace": every match highlighted, the current one with a
 * stronger fill and an outline; quality-bar's Highlight token).
 *
 * The host's find controller searches and hands the ranges over with
 * [MarkdownCodeView.setFindMatches]; the view only draws them. Ranges are
 * kept sorted, so a frame finds the ones on a line by binary search and
 * touches only the matches it draws: 50,000 matches cost the same per
 * frame as 5.
 *
 * Edits made while matches are showing move them with the text; a match
 * an edit touches is dropped (it may no longer match), and an undo or
 * redo, whose extent is not tracked here, clears them all until the host
 * searches again.
 */
internal class FindHighlights {
    private var starts = IntArray(0)
    private var ends = IntArray(0)

    /** Index of the current match, or -1. */
    var current: Int = -1
        private set

    /** Number of matches. */
    val count: Int get() = starts.size

    /** Replaces the matches. Unsorted or overlapping input is sorted and kept as given. */
    fun set(
        newStarts: IntArray,
        newEnds: IntArray,
        newCurrent: Int,
    ) {
        require(newStarts.size == newEnds.size) { "${newStarts.size} starts but ${newEnds.size} ends" }
        val order = newStarts.indices.sortedBy { newStarts[it] }
        starts = IntArray(order.size) { newStarts[order[it]] }
        ends = IntArray(order.size) { maxOf(newStarts[order[it]], newEnds[order[it]]) }
        current = if (newCurrent in order.indices) order.indexOf(newCurrent) else -1
    }

    fun clear() {
        starts = IntArray(0)
        ends = IntArray(0)
        current = -1
    }

    fun startOf(index: Int): Int = starts[index]

    fun endOf(index: Int): Int = ends[index]

    /** The first match that ends after [offset] (or touches it, for an empty match), or [count]. */
    fun firstEndingAfter(offset: Int): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ends[mid] < offset || (ends[mid] == offset && starts[mid] < offset)) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * Follows an edit that replaced [removed] chars at [start] with
     * [inserted] chars: matches after it shift, matches it touched go.
     */
    fun onEdit(
        start: Int,
        removed: Int,
        inserted: Int,
    ) {
        if (starts.isEmpty()) return
        val end = start + removed
        val delta = inserted - removed
        val oldStarts = starts
        val oldEnds = ends
        val keep = oldStarts.indices.filter { oldEnds[it] <= start || oldStarts[it] >= end }
        current = keep.indexOf(current)
        starts = IntArray(keep.size) { oldStarts[keep[it]].let { s -> if (s >= end) s + delta else s } }
        ends = IntArray(keep.size) { i -> oldEnds[keep[i]] + if (oldStarts[keep[i]] >= end) delta else 0 }
    }
}
