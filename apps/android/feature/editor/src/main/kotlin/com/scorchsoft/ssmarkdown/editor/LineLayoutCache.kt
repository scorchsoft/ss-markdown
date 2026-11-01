package com.scorchsoft.ssmarkdown.editor

/**
 * Per-line layouts, keyed by **stable line identity + edit version**
 * (docs/robustness.md, "Editor view"), never by content hash, which would
 * cost O(line length) per lookup and can collide.
 *
 * ### The spike's approach to "stable line identity"
 *
 * The piece tree has no per-line IDs, and giving 2M lines an ID array would
 * cost memory and an O(lines) shift per edit. Instead the identity is the
 * line *index*, kept stable by shifting the few cached entries when an
 * edit adds or removes lines (sora-editor's `RenderCache` idea, studied
 * only, LGPL): an edit that touches lines `[first, first + removed]` and
 * leaves `added` breaks in their place drops the entries for the touched
 * lines and moves every entry below them by `added − removed`.
 *
 * Every entry also carries the document version it is valid for. An edit
 * reported through [onEdit] re-stamps the survivors with the new version.
 * A change that bypasses [onEdit] (an undo applied straight to the
 * document, a reload) leaves every stamp stale, so [get] misses and the
 * line is simply measured again: the cache can be slow but never wrong.
 *
 * Bounded to [capacity] entries, least recently used out first. A screen
 * is about 50 lines and the render margin doubles that, so the default
 * keeps several screens of fling history.
 */
class LineLayoutCache(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Entry(
        var version: Long,
        val windowKey: Int,
        val layout: LineRows,
    )

    // Access-ordered LinkedHashMap: iteration order is least recently used first.
    private val entries = LinkedHashMap<Int, Entry>(capacity, LOAD_FACTOR, true)

    /** Number of cached lines; for tests and the benchmark's diagnostics. */
    val size: Int get() = entries.size

    /**
     * The layout of [line] at document [version] for the window that was
     * requested as [windowKey], or null. In No-wrap mode the key is the *requested* window
     * start (the layout's own start may sit one char earlier so as not to
     * split a surrogate pair); in Wrap mode it is the wrap width in px.
     */
    fun get(
        line: Int,
        version: Long,
        windowKey: Int,
    ): LineRows? {
        val entry = entries[line] ?: return null
        return entry.layout.takeIf { entry.version == version && entry.windowKey == windowKey }
    }

    /** Stores [layout] for [line] at [version] under [windowKey], evicting the least recently used entry when full. */
    fun put(
        line: Int,
        version: Long,
        windowKey: Int,
        layout: LineRows,
    ) {
        entries[line] = Entry(version, windowKey, layout)
        if (entries.size > capacity) {
            val eldest = entries.keys.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    /**
     * Follows an edit: [firstLine] is the line the edit started on,
     * [removedBreaks] the line breaks it deleted and [addedBreaks] those it
     * inserted. The touched lines are dropped; later lines shift; every
     * survivor is re-stamped with [newVersion].
     */
    fun onEdit(
        firstLine: Int,
        removedBreaks: Int,
        addedBreaks: Int,
        newVersion: Long,
    ) {
        val lastTouched = firstLine + removedBreaks
        val shift = addedBreaks - removedBreaks
        val survivors = ArrayList<Pair<Int, Entry>>(entries.size)
        for ((line, entry) in entries) {
            when {
                line < firstLine -> survivors += line to entry
                line > lastTouched -> survivors += (line + shift) to entry
                else -> Unit // Touched by the edit: measure again.
            }
        }
        entries.clear()
        for ((line, entry) in survivors) {
            entry.version = newVersion
            entries[line] = entry
        }
    }

    /** Forgets everything, e.g. after the font size or the whole document changes. */
    fun clear(): Unit = entries.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 512
        const val LOAD_FACTOR = 0.75f
    }
}
