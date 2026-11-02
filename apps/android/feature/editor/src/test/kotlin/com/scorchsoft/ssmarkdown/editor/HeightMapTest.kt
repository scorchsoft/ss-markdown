package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HeightMapTest {
    @Test
    fun `with nothing measured every question is arithmetic`() {
        val map = HeightMap(lineCount = 2_000_000, rowHeight = 20)
        assertEquals(40_000_000L, map.contentHeight)
        assertEquals(20_000L, map.lineTop(1_000))
        assertEquals(1_000, map.lineAt(20_019))
        assertEquals(1_999_999, map.lineAt(Long.MAX_VALUE / 2))
        assertEquals(0, map.lineAt(-5))
    }

    @Test
    fun `a measured line moves every line below it by its extra rows`() {
        val map = HeightMap(lineCount = 2_000, rowHeight = 10)
        val moved = map.setRows(700, 4)
        assertEquals(30, moved)
        assertEquals(7_000L, map.lineTop(700))
        assertEquals(7_040L, map.lineTop(701))
        assertEquals(700, map.lineAt(7_039))
        assertEquals(701, map.lineAt(7_040))
        assertEquals(20_030L, map.contentHeight)
        assertTrue(map.isMeasured(700))
        assertFalse(map.isMeasured(701))
    }

    @Test
    fun `re-measuring returns only the change`() {
        val map = HeightMap(lineCount = 10, rowHeight = 10)
        map.setRows(3, 3)
        assertEquals(0, map.setRows(3, 3))
        assertEquals(-10, map.setRows(3, 2))
    }

    @Test
    fun `the estimate applies to unmeasured lines only`() {
        val map = HeightMap(lineCount = 100, rowHeight = 10)
        map.setRows(0, 1)
        map.estimatedRows = 3
        assertEquals(10L, map.lineTop(1))
        assertEquals(40L, map.lineTop(2))
        assertEquals(10L + 99 * 30, map.contentHeight)
    }

    @Test
    fun `edits shift measured rows with their lines`() {
        val map = HeightMap(lineCount = 1_500, rowHeight = 10)
        map.setRows(1_200, 5)
        map.replaceLines(from = 100, removed = 1, inserted = 11) // Enter pressed 10 times on line 100.
        assertEquals(1_510, map.lineCount)
        assertTrue(map.isMeasured(1_210))
        assertEquals(5, map.rowsOf(1_210))
        map.replaceLines(from = 0, removed = 600, inserted = 1)
        assertTrue(map.isMeasured(611))
        assertEquals(5, map.rowsOf(611))
    }

    @Test
    fun `a paste of a million lines splits into blocks and stays consistent`() {
        val map = HeightMap(lineCount = 3, rowHeight = 10)
        map.setRows(2, 2)
        map.replaceLines(from = 1, removed = 1, inserted = 1_000_000)
        assertEquals(1_000_002, map.lineCount)
        assertEquals(2, map.rowsOf(1_000_001))
        assertEquals(1_000_001L * 10, map.lineTop(1_000_001))
    }

    @Test
    fun `removing every line leaves one empty line`() {
        val map = HeightMap(lineCount = 5, rowHeight = 10)
        map.replaceLines(from = 0, removed = 5, inserted = 0)
        assertEquals(1, map.lineCount)
        assertEquals(10L, map.contentHeight)
    }

    @Test
    fun `random edits and measurements agree with a naive model`() {
        val random = Random(42)
        val model = MutableList(3_000) { 0 }
        val map = HeightMap(model.size, rowHeight = 7)
        repeat(400) {
            when (random.nextInt(3)) {
                0 -> {
                    val line = random.nextInt(model.size)
                    val rows = 1 + random.nextInt(6)
                    map.setRows(line, rows)
                    model[line] = rows
                }

                1 -> {
                    val from = random.nextInt(model.size)
                    val removed = random.nextInt(minOf(40, model.size - from) + 1).coerceAtMost(model.size - 1)
                    val inserted = random.nextInt(60)
                    map.replaceLines(from, removed, inserted)
                    repeat(removed) { model.removeAt(from) }
                    repeat(inserted) { model.add(from, 0) }
                    if (model.isEmpty()) model.add(0)
                }

                else -> {
                    map.estimatedRows = 1 + random.nextInt(3)
                }
            }
            val probe = random.nextInt(model.size)
            val expectedTop = model.take(probe).sumOf { (if (it == 0) map.estimatedRows else it).toLong() } * 7
            assertEquals(model.size, map.lineCount)
            assertEquals("top of $probe", expectedTop, map.lineTop(probe))
            assertEquals("line at top of $probe", probe, map.lineAt(expectedTop))
        }
    }

    @Test
    fun `average measured rows rounds`() {
        val map = HeightMap(lineCount = 10, rowHeight = 10)
        assertEquals(1, map.averageMeasuredRows)
        map.setRows(0, 2)
        map.setRows(1, 3)
        assertEquals(3, map.averageMeasuredRows)
    }
}
