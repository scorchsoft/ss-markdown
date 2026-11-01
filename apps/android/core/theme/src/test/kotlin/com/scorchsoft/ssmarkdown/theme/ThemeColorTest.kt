package com.scorchsoft.ssmarkdown.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeColorTest {
    @Test
    fun `parses long and short hex in any case`() {
        assertEquals("#FBF7EF", ThemeColor.parse("#fbf7ef")?.hex)
        assertEquals("#AABBCC", ThemeColor.parse("#abc")?.hex)
        assertEquals("#000000", ThemeColor.parse(" #000000 ")?.hex)
    }

    @Test
    fun `rejects everything that is not an opaque hex colour`() {
        listOf("", "#", "FBF7EF", "#FBF7EF80", "#GGGGGG", "red", "#12345", "rgb(0,0,0)", "#-12").forEach {
            assertNull(it, ThemeColor.parse(it))
        }
    }

    @Test
    fun `channels round trip`() {
        val color = ThemeColor.of(0x12, 0x34, 0x56)
        assertEquals(0x12, color.red)
        assertEquals(0x34, color.green)
        assertEquals(0x56, color.blue)
        assertEquals(color, ThemeColor.of(0xFF123456.toInt()))
        assertEquals(ThemeColor.WHITE, ThemeColor.of(300, 256, 999))
    }
}
