package com.scorchsoft.ssmarkdown.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordFinderTest {
    private val words = WordFinder()

    private fun wordAt(
        text: String,
        offset: Int,
    ): String = words.wordAt(text, offset).let { text.substring(it.first, it.last + 1) }

    @Test
    fun `the word under or just before the offset`() {
        assertEquals("quick", wordAt("the quick fox", 6))
        assertEquals("quick", wordAt("the quick fox", 9)) // Just after the word.
        assertEquals("the", wordAt("the quick fox", 0))
    }

    @Test
    fun `punctuation and spaces select their own run`() {
        assertEquals("**", wordAt("a **b** c", 2))
        assertEquals("   ", wordAt("a   b", 2))
    }

    @Test
    fun `an empty line gives an empty range`() {
        assertEquals("", wordAt("\n\n", 1))
    }

    @Test
    fun `a megabyte token is cut at the window`() {
        val token = "x".repeat(1_000_000)
        val range = words.wordAt(token, 500_000)
        assertTrue(range.last - range.first < 1_000)
    }

    @Test
    fun `next and previous word boundaries`() {
        val text = "one, two three"
        assertEquals(3, words.nextWordEnd(text, 0))
        assertEquals(8, words.nextWordEnd(text, 3))
        assertEquals(5, words.previousWordStart(text, 8))
        assertEquals(0, words.previousWordStart(text, 3))
    }
}
