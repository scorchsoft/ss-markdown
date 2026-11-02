package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.markdown.LineResult

/**
 * Stable identities for lines across edits, so a per-line cache survives
 * edits elsewhere: an edit renews the ids of the lines it touched and
 * only shifts the rest (the "line ID + edit version" key of
 * docs/robustness.md, "Editor view").
 *
 * The ids sit in a gap buffer whose spare room follows the last edit
 * (performance review PERF-E): a run of line breaks in one place moves
 * nothing after the first, where a flat array moved the whole tail, 1.4M
 * ids, on every Enter near the top of a 20 MB file.
 */
internal class LineVersions(
    lineCount: Int = 1,
) {
    private var size = maxOf(lineCount, 1)
    private var nextId = size
    private var ids = IntArray(size + INITIAL_GAP) { it }
    private var gapStart = size
    private var gapLength = INITIAL_GAP

    /** The id of [line]'s current content. */
    operator fun get(line: Int): Int = ids[if (line < gapStart) line else line + gapLength]

    val lineCount: Int get() = size

    /**
     * Entries moved by edits so far: none for a same-line-count edit
     * (PERF-17), none after the first for a run of breaks in one place (PERF-E).
     */
    var shiftedEntries: Long = 0
        private set

    /** Starts over for a document of [lineCount] lines; every old id is retired. */
    fun reset(lineCount: Int) {
        size = maxOf(lineCount, 1)
        ids = IntArray(size + INITIAL_GAP) { nextId + it }
        nextId += size
        gapStart = size
        gapLength = INITIAL_GAP
    }

    /** Follows [change]: its lines get fresh ids, lines below shift. */
    fun onEdit(change: LineChange) {
        val first = change.firstLine.coerceIn(0, size - 1)
        val removed = (change.removedBreaks + 1).coerceAtMost(size - first)
        val added = change.addedBreaks + 1
        if (added == removed) {
            // A keystroke inside a line moves nothing.
            for (line in first until first + added) ids[slot(line)] = nextId++
            return
        }
        moveGapTo(first + removed)
        gapStart = first
        gapLength += removed
        size -= removed
        if (added > gapLength) grow(added)
        for (at in gapStart until gapStart + added) ids[at] = nextId++
        gapStart += added
        gapLength -= added
        size += added
    }

    private fun slot(line: Int): Int = if (line < gapStart) line else line + gapLength

    private fun moveGapTo(line: Int) {
        if (line < gapStart) {
            System.arraycopy(ids, line, ids, line + gapLength, gapStart - line)
            shiftedEntries += gapStart - line
        } else if (line > gapStart) {
            System.arraycopy(ids, gapStart + gapLength, ids, gapStart, line - gapStart)
            shiftedEntries += line - gapStart
        }
        gapStart = line
    }

    private fun grow(needed: Int) {
        val newGap = maxOf(needed, size / GROWTH_DIVISOR, INITIAL_GAP)
        val out = IntArray(size + newGap)
        val tail = ids.size - gapStart - gapLength
        System.arraycopy(ids, 0, out, 0, gapStart)
        System.arraycopy(ids, gapStart + gapLength, out, gapStart + newGap, tail)
        ids = out
        gapLength = newGap
    }

    private companion object {
        const val INITIAL_GAP = 256
        const val GROWTH_DIVISOR = 8
    }
}

/**
 * A bounded least-recently-used cache of per-line drawings, keyed by the
 * line's [LineVersions] id plus everything else the drawing depends on
 * ([LineDrawKey]). Generic over the drawing so the policy is tested on the
 * host JVM; the view stores `RenderNode`s in it.
 *
 * @param capacity drawings kept; a few screens' worth.
 * @param release frees a drawing that falls out (`RenderNode.discardDisplayList`).
 */
internal class LineDrawCache<T : Any>(
    private val capacity: Int,
    private val release: (T) -> Unit = {},
) {
    private class Entry<T>(
        val key: LineDrawKey,
        val drawing: T,
    )

    private val entries = LinkedHashMap<Int, Entry<T>>(capacity, LOAD_FACTOR, true)

    /** Drawings recorded, for tests and the benchmark. */
    var recordings: Int = 0
        private set

    val size: Int get() = entries.size

    /** The drawing for [lineId] under [key], recording it with [record] when missing or stale. */
    fun get(
        lineId: Int,
        key: LineDrawKey,
        record: (T?) -> T,
    ): T {
        val entry = entries[lineId]
        if (entry != null && entry.key == key) return entry.drawing
        val drawing = record(entry?.drawing)
        recordings++
        // The caller may reuse [key] as a probe for every line, every frame: store a copy.
        entries[lineId] = Entry(key.copy(), drawing)
        if (entries.size > capacity) evictEldest()
        return drawing
    }

    /** Drops everything: the font, theme or wrap width changed. */
    fun clear() {
        entries.values.forEach { release(it.drawing) }
        entries.clear()
    }

    private fun evictEldest() {
        val eldest = entries.entries.iterator()
        val first = eldest.next()
        release(first.value.drawing)
        eldest.remove()
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * Everything besides the line's text that its drawing depends on. A
 * drawing is reused only when all of it matches.
 *
 * @property tokens the tokenizer's result for the line: a line whose
 *   tokens changed (its block state changed above it) is redrawn; a fresh
 *   result with the same tokens (a heuristic guess the accurate pass
 *   confirmed) is not.
 * @property layout the line's layout, by identity: a re-measured line
 *   (new window, wrap width or font) is redrawn.
 * @property epoch bumped when the font, theme or layout width changes.
 * @property left the text's x origin, which follows horizontal scroll in No wrap.
 * @property width the drawing's width.
 */
internal class LineDrawKey(
    tokens: Any,
    layout: Any,
    epoch: Int,
    left: Float,
    width: Int,
) {
    var tokens: Any = tokens
        private set
    var layout: Any = layout
        private set
    var epoch: Int = epoch
        private set
    var left: Float = left
        private set
    var width: Int = width
        private set

    /** Reuses this key as a lookup probe, so the draw path allocates no key per line per frame (PERF-19). */
    fun set(
        tokens: Any,
        layout: Any,
        epoch: Int,
        left: Float,
        width: Int,
    ): LineDrawKey {
        this.tokens = tokens
        this.layout = layout
        this.epoch = epoch
        this.left = left
        this.width = width
        return this
    }

    /** An independent key with the same values, for storing. */
    fun copy(): LineDrawKey = LineDrawKey(tokens, layout, epoch, left, width)

    override fun equals(other: Any?): Boolean =
        other is LineDrawKey &&
            sameTokens(other.tokens) &&
            other.layout === layout &&
            other.epoch == epoch &&
            other.left == left &&
            other.width == width

    override fun hashCode(): Int = System.identityHashCode(layout) * HASH + epoch

    private fun sameTokens(other: Any): Boolean {
        val mine = tokens
        return other === mine || (mine is LineResult && other is LineResult && mine.sameAs(other))
    }

    private companion object {
        const val HASH = 31
    }
}
