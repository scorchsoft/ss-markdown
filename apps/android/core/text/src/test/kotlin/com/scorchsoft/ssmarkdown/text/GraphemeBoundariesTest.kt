package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemeBoundariesTest {
    private val graphemes = GraphemeBoundaries()

    private val family = "👨‍👩‍👧" // 👨‍👩‍👧
    private val flag = "🇬🇧" // 🇬🇧
    private val thumb = "👍🏽" // 👍🏽
    private val accent = "é"

    @Test
    fun `moves over whole clusters both ways`() {
        val clusters = listOf("a", family, flag, thumb, accent, "\r\n", "b")
        val text = clusters.joinToString("")
        val boundaries = clusters.runningFold(0) { acc, s -> acc + s.length }
        for (i in 0 until boundaries.size - 1) {
            assertEquals(boundaries[i + 1], graphemes.next(text, boundaries[i]))
            assertEquals(boundaries[i], graphemes.previous(text, boundaries[i + 1]))
        }
        assertEquals(text.length, graphemes.next(text, text.length))
        assertEquals(0, graphemes.previous(text, 0))
    }

    @Test
    fun `knows a boundary from the inside of a cluster`() {
        val text = "x$family"
        assertTrue(graphemes.isBoundary(text, 1))
        assertFalse(graphemes.isBoundary(text, 2))
        assertFalse(graphemes.isBoundary(text, 4))
    }

    @Test
    fun `works on a piece tree without materialising it`() {
        val tree = PieceTree.of("x".repeat(500_000) + flag + "y")
        assertEquals(500_000 + flag.length, graphemes.next(tree, 500_000))
        assertEquals(500_000, graphemes.previous(tree, 500_000 + flag.length))
    }

    @Test
    fun `widens its window for an absurdly long cluster`() {
        val zalgo = "a" + "́".repeat(500)
        val text = "<$zalgo>"
        assertEquals(1 + zalgo.length, graphemes.next(text, 1))
        assertEquals(1, graphemes.previous(text, 1 + zalgo.length))
    }
}
