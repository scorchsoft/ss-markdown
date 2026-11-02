package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollingTest {
    private val map = HeightMap(lineCount = 1_000, rowHeight = 20)

    @Test
    fun `visible lines include partly shown ones`() {
        assertEquals(0..4, map.visibleLines(scrollY = 0, viewportHeight = 100))
        assertEquals(0..5, map.visibleLines(scrollY = 10, viewportHeight = 100))
    }

    @Test
    fun `the render margin leans towards the scroll direction`() {
        val ahead = map.renderLines(scrollY = 2_000, viewportHeight = 400, direction = ScrollDirection.TOWARDS_END)
        assertEquals(95, ahead.first) // A quarter screen behind.
        assertEquals(139, ahead.last) // A full screen ahead.
        val back = map.renderLines(scrollY = 2_000, viewportHeight = 400, direction = ScrollDirection.TOWARDS_START)
        assertEquals(80, back.first)
        assertEquals(124, back.last)
        val idle = map.renderLines(scrollY = 2_000, viewportHeight = 400, direction = ScrollDirection.IDLE)
        assertEquals(90..129, idle)
    }

    @Test
    fun `reveal scrolls the least distance`() {
        assertEquals(500, revealScroll(top = 600, bottom = 620, scrollY = 500, viewportHeight = 400))
        assertEquals(400, revealScroll(top = 400, bottom = 420, scrollY = 500, viewportHeight = 400))
        assertEquals(520, revealScroll(top = 900, bottom = 920, scrollY = 500, viewportHeight = 400))
    }

    @Test
    fun `max scroll keeps the last line at the bottom and never goes negative`() {
        assertEquals(19_600, map.maxScrollY(400))
        assertEquals(0, HeightMap(1, 20).maxScrollY(400))
    }
}
