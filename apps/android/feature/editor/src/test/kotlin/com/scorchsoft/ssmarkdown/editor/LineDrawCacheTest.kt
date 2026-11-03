package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-line drawing cache's policy, on the host JVM with strings standing in for RenderNodes. */
class LineDrawCacheTest {
    private val tokens = Any()
    private val layout = Any()

    private fun key(
        epoch: Int = 0,
        left: Float = 0f,
        tokens: Any = this.tokens,
        layout: Any = this.layout,
    ) = LineDrawKey(tokens, layout, epoch, left, WIDTH)

    @Test
    fun `an edit renews only the touched lines' ids and shifts the rest`() {
        val versions = LineVersions(5)
        val before = (0 until 5).map { versions[it] }
        versions.onEdit(LineChange(firstLine = 1, removedBreaks = 0, addedBreaks = 2))
        assertEquals(7, versions.lineCount)
        assertEquals(before[0], versions[0])
        assertEquals(before.drop(2), (4 until 7).map { versions[it] })
        assertEquals(3, (1..3).map { versions[it] }.filter { it !in before }.size)

        versions.onEdit(LineChange(firstLine = 0, removedBreaks = 3, addedBreaks = 0))
        assertEquals(4, versions.lineCount)
        assertEquals(before.drop(2), (1 until 4).map { versions[it] })
        versions.reset(2)
        assertEquals(2, versions.lineCount)
        assertNotEquals(before[0], versions[0])
    }

    @Test
    fun `a drawing is reused while its key holds and redrawn when anything changes`() {
        val released = mutableListOf<String>()
        val cache = LineDrawCache<String>(capacity = 3) { released += it }
        var n = 0
        val record = { _: String? -> "d${n++}" }
        val first = cache.get(1, key(), record)
        assertSame(first, cache.get(1, key(), record))
        assertEquals(1, cache.recordings)
        assertNotEquals(first, cache.get(1, key(tokens = Any()), record))
        assertNotEquals("d1", cache.get(1, key(layout = Any()), record))
        cache.get(1, key(epoch = 1), record)
        cache.get(1, key(epoch = 1, left = -40f), record)
        assertEquals(5, cache.recordings)
    }

    @Test
    fun `the oldest drawing is released past capacity, and clear releases all`() {
        val released = mutableListOf<String>()
        val cache = LineDrawCache<String>(capacity = 2) { released += it }
        cache.get(1, key()) { "one" }
        cache.get(2, key()) { "two" }
        cache.get(1, key()) { "never" }
        cache.get(3, key()) { "three" }
        assertEquals(listOf("two"), released)
        cache.clear()
        assertEquals(setOf("two", "one", "three"), released.toSet())
        assertEquals(0, cache.size)
    }

    @Test
    fun `a stale drawing is handed back for reuse`() {
        val cache = LineDrawCache<StringBuilder>(capacity = 2)
        val first = cache.get(7, key()) { StringBuilder("a") }
        val second = cache.get(7, key(epoch = 2)) { reused -> reused ?: StringBuilder("new") }
        assertSame(first, second)
    }

    @Test
    fun `a drawing the platform emptied is recorded again even though its key still holds`() {
        // A RenderNode's display list is dropped by the platform once nothing draws it.
        val live = mutableMapOf<Int, Boolean>()
        var n = 0
        val cache = LineDrawCache<Int>(capacity = 8, isLive = { live.getValue(it) })
        val record = { reused: Int? -> (reused ?: n++).also { live[it] = true } }
        val drawn = (1..3).map { cache.get(it, key(), record) }
        assertEquals(3, cache.recordings)

        // Lines 1..3 scroll off screen: the platform discards their content, the cache keeps them.
        drawn.forEach { live[it] = false }
        val back = (1..3).map { cache.get(it, key(), record) }

        assertEquals("the same nodes are reused", drawn, back)
        assertEquals("each is recorded again before it is drawn", 6, cache.recordings)
        assertEquals(3, cache.revived)
        back.forEach { assertTrue("line $it has content again", live.getValue(it)) }
        cache.get(1, key(), record)
        assertEquals("a live drawing is replayed", 6, cache.recordings)
    }

    private companion object {
        const val WIDTH = 500
    }
}
