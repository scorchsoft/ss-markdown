package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-keystroke and per-frame work in the editor's caches stays bounded
 * on a huge file (performance review PERF-17, PERF-18, PERF-19). Counts
 * and allocated bytes, never wall-clock time.
 */
class EditorCacheWorkTest {
    // The host JVM's per-thread allocation counter, by reflection:
    // unit tests compile against android.jar.
    private val threads =
        Class
            .forName(
                "java.lang.management.ManagementFactory",
            ).getMethod("getThreadMXBean")
            .invoke(null)
    private val allocated =
        Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.java)

    private fun allocatedBytes(): Long = allocated.invoke(threads, Thread.currentThread().id) as Long

    private inline fun allocatedBy(block: () -> Unit): Long {
        val before = allocatedBytes()
        block()
        return allocatedBytes() - before
    }

    @Test
    fun `typing inside a line moves no line id (PERF-17)`() {
        val versions = LineVersions(HUGE_LINES)
        repeat(KEYS) { versions.onEdit(LineChange(firstLine = 10, removedBreaks = 0, addedBreaks = 0)) }
        // Before: the whole tail, 1.46M ids, was copied onto itself on every key.
        assertEquals(0L, versions.shiftedEntries)
        versions.onEdit(LineChange(firstLine = 10, removedBreaks = 0, addedBreaks = 1))
        assertEquals((HUGE_LINES - 11).toLong(), versions.shiftedEntries)
    }

    @Test
    fun `typing inside a line rebuilds no layout map (PERF-17)`() {
        val cache = LineLayoutCache()
        val layout = LineLayout(4, 0, "line".toCharArray(), FloatArray(5) { it * 10f }, 10f)
        repeat(CACHED) { cache.put(it, version = 0, windowKey = 0, layout) }
        var version = 0L
        repeat(WARM_UP) { cache.onEdit(firstLine = 600, removedBreaks = 0, addedBreaks = 0, newVersion = ++version) }
        val bytes = allocatedBy { repeat(KEYS) { cache.onEdit(600, 0, 0, ++version) } }
        // Before: a list of 512 Pairs and boxed keys per key, ~25 KB each, 25 MB for 1,000 keys.
        assertTrue("$bytes bytes for $KEYS keys", bytes < KEYS * SMALL_BYTES_PER_KEY)
        assertEquals(CACHED, cache.size)
        assertEquals(layout, cache.get(5, version, 0))
    }

    @Test
    fun `height queries take a logarithmic number of steps and allocate nothing (PERF-18)`() {
        val heights = HeightMap(HUGE_LINES, ROW)
        for (line in 0 until HUGE_LINES step 997) heights.setRows(line, 3)
        repeat(WARM_UP) { heights.lineTop(it * 1_000) }
        val stepsBefore = heights.searchSteps
        val bytes =
            allocatedBy {
                for (i in 0 until QUERIES) {
                    val line = (i * 1_459L % HUGE_LINES).toInt()
                    heights.lineAt(heights.lineTop(line))
                    heights.heightOf(line)
                }
            }
        val steps = heights.searchSteps - stepsBefore
        // ~2,850 blocks: 12 steps a search. Before, each query walked up to every block.
        assertTrue("$steps steps for ${QUERIES * 3} searches", steps <= QUERIES * 3L * 12)
        assertTrue("$bytes bytes allocated", bytes < SMALL_BYTES)
    }

    @Test
    fun `a reused probe key never changes a stored drawing's key (PERF-19)`() {
        val cache = LineDrawCache<String>(4)
        val probe = LineDrawKey(Unit, Unit, 0, 0f, 100)
        val tokens = Any()
        val layout = Any()
        cache.get(1, probe.set(tokens, layout, 0, 0f, 100)) { "one" }
        cache.get(2, probe.set(Any(), Any(), 0, 0f, 100)) { "two" }
        assertEquals("one", cache.get(1, probe.set(tokens, layout, 0, 0f, 100)) { "redrawn" })
        assertEquals(2, cache.recordings)
    }

    private companion object {
        const val HUGE_LINES = 1_460_000
        const val KEYS = 1_000
        const val CACHED = 512
        const val WARM_UP = 200
        const val QUERIES = 1_000
        const val ROW = 20
        const val SMALL_BYTES = 64 * 1024L
        const val SMALL_BYTES_PER_KEY = 256L
    }
}
