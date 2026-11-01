package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LineLayoutCacheTest {
    private fun layout(text: String) =
        LineLayout(text.length, 0, text.toCharArray(), FloatArray(text.length + 1) { it * 10f }, 10f)

    private fun cacheWithLines(vararg lines: Int): Pair<LineLayoutCache, Map<Int, LineLayout>> {
        val cache = LineLayoutCache()
        val layouts = lines.associateWith { layout("line $it") }
        layouts.forEach { (line, layout) -> cache.put(line, version = 1, windowKey = 0, layout) }
        return cache to layouts
    }

    @Test
    fun `hit needs the same line, version and window`() {
        val (cache, layouts) = cacheWithLines(3)
        assertSame(layouts[3], cache.get(3, 1, 0))
        assertNull(cache.get(3, 2, 0))
        assertNull(cache.get(3, 1, 500))
        assertNull(cache.get(4, 1, 0))
    }

    @Test
    fun `inserting lines shifts the entries below and drops the edited line`() {
        val (cache, layouts) = cacheWithLines(1, 5, 9)
        // Typing Enter twice on line 5: one line touched, two breaks added.
        cache.onEdit(firstLine = 5, removedBreaks = 0, addedBreaks = 2, newVersion = 2)
        assertSame(layouts[1], cache.get(1, 2, 0))
        assertNull(cache.get(5, 2, 0))
        assertSame(layouts[9], cache.get(11, 2, 0))
        assertNull(cache.get(9, 2, 0))
    }

    @Test
    fun `deleting lines shifts up and drops every touched line`() {
        val (cache, layouts) = cacheWithLines(2, 3, 4, 10)
        // Delete from line 2 to line 4 (two breaks removed).
        cache.onEdit(firstLine = 2, removedBreaks = 2, addedBreaks = 0, newVersion = 2)
        assertNull(cache.get(2, 2, 0))
        assertSame(layouts[10], cache.get(8, 2, 0))
        assertEquals(1, cache.size)
    }

    @Test
    fun `an edit the cache never heard about invalidates by version`() {
        val (cache, _) = cacheWithLines(1)
        // The document moved to version 7 without onEdit (an undo applied directly).
        assertNull(cache.get(1, 7, 0))
    }

    @Test
    fun `capacity evicts the least recently used line`() {
        val cache = LineLayoutCache(capacity = 2)
        cache.put(1, 1, 0, layout("a"))
        cache.put(2, 1, 0, layout("b"))
        assertNotNull(cache.get(1, 1, 0))
        cache.put(3, 1, 0, layout("c"))
        assertNull(cache.get(2, 1, 0))
        assertNotNull(cache.get(1, 1, 0))
        assertNotNull(cache.get(3, 1, 0))
    }
}
