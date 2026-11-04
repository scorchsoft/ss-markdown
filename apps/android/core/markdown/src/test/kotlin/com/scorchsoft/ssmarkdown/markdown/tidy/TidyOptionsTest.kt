package com.scorchsoft.ssmarkdown.markdown.tidy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Tidy's two conventions (Andrew, 2026-11-04). */
class TidyOptionsTest {
    private fun tidy(
        text: String,
        options: TidyOptions = TidyOptions(),
    ) = MarkdownTidy.plan(text, options).applyTo(text)

    private val keepRepeated = TidyOptions(countUpRepeatedLists = false)
    private val keepBlanks = TidyOptions(collapseBlankLines = false)

    @Test
    fun `by default a 1 1 1 list counts up`() {
        assertEquals("1. a\n2. b\n3. c\n", tidy("1. a\n1. b\n1. c\n"))
    }

    @Test
    fun `kept, a list of one repeated number is left as written`() {
        assertEquals("1. a\n1. b\n1. c\n", tidy("1. a\n1. b\n1. c\n", keepRepeated))
        assertTrue(MarkdownTidy.plan("1. a\n1. b\n1. c\n", keepRepeated).isEmpty)
    }

    @Test
    fun `kept, a list that is out of order still counts up`() {
        assertEquals("1. a\n2. b\n3. c\n", tidy("1. a\n5. b\n2. c\n", keepRepeated))
    }

    @Test
    fun `by default extra blank lines collapse`() {
        assertEquals("a\n\nb\n", tidy("a\n\n\n\nb\n"))
    }

    @Test
    fun `kept, extra blank lines stay, and only whitespace on them goes`() {
        assertEquals("a\n\n\n\nb\n", tidy("a\n\n  \n\t\nb\n", keepBlanks))
        assertEquals(2, MarkdownTidy.plan("a\n\n  \n\t\nb\n", keepBlanks).counts[TidyRule.TrailingSpaces])
        assertEquals(null, MarkdownTidy.plan("a\n\n\n\nb\n", keepBlanks).counts[TidyRule.ExtraBlankLines])
    }

    @Test
    fun `kept, the other rules still apply`() {
        assertEquals("# T\n\n\n\ntext\n", tidy("#   T\n\n\n\ntext  \n", keepBlanks))
    }
}
