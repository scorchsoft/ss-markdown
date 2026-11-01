package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomModelTest {
    @Test
    fun `100 percent is 12_5 sp`() {
        assertEquals(12.5f, ZoomModel().textSizeSp, 0f)
        assertEquals(25f, ZoomModel(200).textSizeSp, 0f)
    }

    @Test
    fun `pinch steps multiply without drifting`() {
        val zoom = ZoomModel()
        repeat(10) { zoom.scaleBy(1.01f) }
        repeat(10) { zoom.scaleBy(1 / 1.01f) }
        assertEquals(100, zoom.percent)
    }

    @Test
    fun `zoom is clamped`() {
        val zoom = ZoomModel()
        zoom.scaleBy(100f)
        assertEquals(ZoomModel.MAX_PERCENT, zoom.percent)
        zoom.scaleBy(0.0001f)
        assertEquals(ZoomModel.MIN_PERCENT, zoom.percent)
        assertFalse(zoom.scaleBy(Float.NaN))
        assertFalse(zoom.scaleBy(0f))
    }

    @Test
    fun `set reports a change`() {
        val zoom = ZoomModel()
        assertFalse(zoom.set(100))
        assertTrue(zoom.set(120))
    }

    @Test
    fun `the haptic tick fires when a pinch crosses or lands on 100`() {
        assertTrue(ZoomModel.crossesDefault(98, 103))
        assertTrue(ZoomModel.crossesDefault(103, 100))
        assertFalse(ZoomModel.crossesDefault(100, 101))
        assertFalse(ZoomModel.crossesDefault(110, 120))
    }
}
