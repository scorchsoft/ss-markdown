package com.scorchsoft.ssmarkdown.media

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/** The image viewer zooms about the fingers (the centroid), not the middle of the picture. */
class PinchZoomTest {
    private val centre = Offset(500f, 1000f)

    /** Where layout point [p] is drawn under a layer scaled by [scale] about [centre] and moved by [offset]. */
    private fun drawn(
        p: Offset,
        scale: Float,
        offset: Offset,
    ) = centre + offset + (p - centre) * scale

    @Test
    fun `the point under the fingers stays under them`() {
        val fingers = Offset(800f, 400f)
        val before = PinchZoom(scale = 1.5f, offset = Offset(30f, -20f))
        // The content point the fingers are on.
        val content = centre + (fingers - centre - before.offset) / before.scale
        val after = before.zoomed(zoom = 2f, pan = Offset.Zero, centroid = fingers, centre = centre, maxScale = 8f)
        assertEquals(3f, after.scale)
        val now = drawn(content, after.scale, after.offset)
        assertEquals(fingers.x, now.x, 0.01f)
        assertEquals(fingers.y, now.y, 0.01f)
    }

    @Test
    fun `pan moves the picture and zooming back out recentres it`() {
        val panned = PinchZoom(2f, Offset.Zero).zoomed(1f, Offset(10f, 5f), centre, centre, 8f)
        assertEquals(Offset(10f, 5f), panned.offset)
        val out = panned.zoomed(0.1f, Offset.Zero, centre, centre, 8f)
        assertEquals(PinchZoom(1f, Offset.Zero), out)
    }
}
