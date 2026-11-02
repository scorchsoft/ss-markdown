package com.scorchsoft.ssmarkdown.markdown

/**
 * A few values keyed by line, kept sorted by line in two parallel arrays:
 * the storage behind [Outline] and [ReferenceIndex].
 *
 * A document has far fewer headings or definitions than lines, so an edit
 * that shifts every later entry is a tight `IntArray` loop over those few
 * (10k headings shift in microseconds), and lookups are binary searches.
 * A balanced tree would buy O(log n) shifts at the cost of an object per
 * entry; it is not needed at these sizes.
 */
@Suppress("TooManyFunctions") // Lookups, edits, a copy and its size estimate: each a few lines.
internal class LineKeyedList<T : Any> {
    private var lines = IntArray(INITIAL)
    private var values = arrayOfNulls<Any>(INITIAL)

    var size: Int = 0
        private set

    /** Bumped on every structural change, so derived caches know when to rebuild. */
    var version: Int = 0
        private set

    fun lineAt(index: Int): Int = lines[index]

    @Suppress("UNCHECKED_CAST") // Only T is ever stored.
    fun valueAt(index: Int): T = values[index] as T

    /** Index of the entry on [line], or -1. */
    fun indexOf(line: Int): Int {
        val i = lowerBound(line)
        return if (i < size && lines[i] == line) i else -1
    }

    /** Index of the last entry at or before [line], or -1. */
    fun indexAtOrBefore(line: Int): Int = lowerBound(line + 1) - 1

    /** Puts [value] on [line], or removes that line's entry when [value] is null. */
    fun set(
        line: Int,
        value: T?,
    ) {
        val i = lowerBound(line)
        val present = i < size && lines[i] == line
        when {
            value == null && present -> removeRange(i, i + 1)
            value == null || (present && values[i] == value) -> return
            present -> values[i] = value
            else -> insertAt(i, line, value)
        }
        version++
    }

    /**
     * Lines `[from, from + removed)` were replaced by [inserted] new lines:
     * drops their entries and shifts every later one.
     */
    fun replaceLines(
        from: Int,
        removed: Int,
        inserted: Int,
    ) {
        val first = lowerBound(from)
        val last = lowerBound(from + removed)
        val delta = inserted - removed
        if (last == first && (delta == 0 || first == size)) return
        if (last > first) removeRange(first, last)
        if (delta != 0) for (i in first until size) lines[i] += delta
        version++
    }

    /** An independent copy; the values are shared, so they must be immutable. */
    fun copy(): LineKeyedList<T> {
        val copy = LineKeyedList<T>()
        copy.lines = lines.copyOf()
        copy.values = values.copyOf()
        copy.size = size
        return copy
    }

    /**
     * Roughly the heap these entries hold: the two arrays at capacity, plus
     * [perValue] for each value (its own objects).
     */
    fun retainedBytesEstimate(perValue: (T) -> Long): Long {
        var bytes = lines.size.toLong() * (Int.SIZE_BYTES + REFERENCE_BYTES)
        for (i in 0 until size) bytes += perValue(valueAt(i))
        return bytes
    }

    private fun lowerBound(line: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (lines[mid] < line) low = mid + 1 else high = mid
        }
        return low
    }

    private fun insertAt(
        index: Int,
        line: Int,
        value: T,
    ) {
        if (size == lines.size) {
            lines = lines.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        System.arraycopy(lines, index, lines, index + 1, size - index)
        System.arraycopy(values, index, values, index + 1, size - index)
        lines[index] = line
        values[index] = value
        size++
    }

    private fun removeRange(
        from: Int,
        to: Int,
    ) {
        System.arraycopy(lines, to, lines, from, size - to)
        System.arraycopy(values, to, values, from, size - to)
        val newSize = size - (to - from)
        values.fill(null, newSize, size)
        size = newSize
    }

    private companion object {
        const val INITIAL = 16

        /** A compressed reference, on ART and on a 64-bit JVM with compressed oops. */
        const val REFERENCE_BYTES = 4
    }
}

/** A `String`'s own heap: the object, its array header and one byte a char (ART and the JVM compress ASCII). */
internal fun stringBytes(text: String): Long = STRING_OVERHEAD_BYTES + text.length

/** The `String` object and its backing array's header, rounded. */
private const val STRING_OVERHEAD_BYTES = 40L
