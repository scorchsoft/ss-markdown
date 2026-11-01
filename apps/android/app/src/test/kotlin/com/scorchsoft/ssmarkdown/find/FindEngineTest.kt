package com.scorchsoft.ssmarkdown.find

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindEngineTest {
    private fun starts(result: FindResult) = (result as FindResult.Found).matches.map { it.start }

    @Test
    fun `plain search ignores case and treats regex characters literally`() {
        assertEquals(listOf(0, 8), starts(FindEngine.find("Save. A save.", "save", FindOptions())))
        assertEquals(listOf(4), starts(FindEngine.find("a.b a*b", "a*b", FindOptions())))
    }

    @Test
    fun `match case and whole word narrow it`() {
        val text = "save Save saved"
        assertEquals(listOf(5), starts(FindEngine.find(text, "Save", FindOptions(matchCase = true))))
        assertEquals(listOf(0, 5), starts(FindEngine.find(text, "save", FindOptions(wholeWord = true))))
    }

    @Test
    fun `regex finds groups and replaces with them`() {
        val text = "v1 v22"
        val found = FindEngine.find(text, "v(\\d+)", FindOptions(regex = true)) as FindResult.Found
        assertEquals(2, found.matches.size)
        assertEquals(
            "n22",
            FindEngine.replacementFor(text, found.matches[1], "v(\\d+)", "n$1", FindOptions(regex = true)),
        )
    }

    @Test
    fun `a bad pattern is reported, not thrown`() {
        assertEquals(FindResult.InvalidPattern, FindEngine.find("x", "(", FindOptions(regex = true)))
    }

    @Test
    fun `empty matches are skipped so replace all cannot loop`() {
        assertTrue(starts(FindEngine.find("abc", "x*", FindOptions(regex = true))).isEmpty())
    }

    @Test
    fun `the current match is the first at or after the caret and wraps`() {
        val result = FindEngine.find("a a a", "a", FindOptions())
        val state = FindUiState(query = "a").withResult(result, caret = 1)
        assertEquals(1, state.current)
        assertEquals(2, state.next().current)
        assertEquals(0, state.next().next().current)
        assertEquals(2, state.copy(current = 0).previous().current)
    }

    @Test
    fun `a cancelled search stops reading within one poll, even mid-scan with no match (PERF-8)`() {
        val hay = "hay ".repeat(500_000) // 2M chars, no needle: one long Matcher.find call.
        val text = CancellableText(hay) { true }
        val thrown =
            runCatching { FindEngine.find(text, "needle", FindOptions()) }
                .exceptionOrNull()
        assertTrue(thrown is CancellableText.SearchCancelled)
        assertTrue("read ${text.reads} chars of ${hay.length}", text.reads <= 16_384)
    }

    @Test
    fun `an uncancelled search through the wrapper finds the same matches`() {
        val text = CancellableText("a b a", cancelled = { false })
        assertEquals(listOf(0, 4), starts(FindEngine.find(text, "a", FindOptions())))
    }
}
