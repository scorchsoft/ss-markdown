package com.scorchsoft.ssmarkdown.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [LinearSyntax] answers exactly as the regexes it replaced, over random
 * lines made of the characters that matter, and it does so on lines long
 * enough to overflow the stack of those regexes.
 */
class LinearSyntaxTest {
    private val tableDelimiter = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
    private val pasteDivider = Regex("""^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")
    private val rule = Regex("""^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")
    private val dashRule = Regex("""^-(?=(?:[ \t]*-){2,}[ \t]*$)""")

    private fun line(random: Random): String {
        val alphabet = " \t|:-*_x\u000B\r\u000C"
        return String(CharArray(random.nextInt(0, 14)) { alphabet[random.nextInt(alphabet.length)] })
    }

    @Test
    fun `every scan agrees with its regex on random lines`() {
        val random = Random(7)
        repeat(SAMPLES) {
            val s = line(random)
            val show = s.replace("\t", "⇥")
            assertEquals(
                "delimiter \"$show\"",
                tableDelimiter.matches(s),
                LinearSyntax.isDelimiterRow(s, LinearSyntax::isRegexSpace),
            )
            assertEquals(
                "divider \"$show\"",
                pasteDivider.matches(s),
                LinearSyntax.isDelimiterRow(s, LinearSyntax::isSpaceOrTab),
            )
            assertEquals("rule \"$show\"", rule.matches(s), LinearSyntax.isThematicBreak(s))
            // Java's `$` also matches before a final `\r`; the TypeScript twin's (and the scan) does not. Lines
            // reach escapeLineStart with CRs already normalised, so only CR-free lines are compared.
            if ('\r' !in s) {
                assertEquals("dash rule \"$show\"", dashRule.containsMatchIn(s), LinearSyntax.isRunOf(s, 0, '-', 3))
            }
        }
    }

    @Test
    fun `known lines`() {
        val space = LinearSyntax::isRegexSpace
        assertTrue(LinearSyntax.isDelimiterRow("|---|:-:|--:|", space))
        assertTrue(LinearSyntax.isDelimiterRow("--- | ---", space))
        assertTrue(LinearSyntax.isDelimiterRow(" :- ", space))
        assertFalse(LinearSyntax.isDelimiterRow("|", space))
        assertFalse(LinearSyntax.isDelimiterRow("||", space))
        assertFalse(LinearSyntax.isDelimiterRow("|--||", space))
        assertFalse(LinearSyntax.isDelimiterRow("| :: |", space))
        assertFalse(LinearSyntax.isDelimiterRow("| -x- |", space))
        assertTrue(LinearSyntax.isThematicBreak("   * * *"))
        assertFalse(LinearSyntax.isThematicBreak("    ***"))
        assertFalse(LinearSyntax.isThematicBreak("*-*"))
    }

    @Test
    fun `lines that overflowed the regexes' stack are answered`() {
        val wide = "| " + "--- | ".repeat(20_000)
        assertTrue(LinearSyntax.isDelimiterRow(wide, LinearSyntax::isRegexSpace))
        assertTrue(LinearSyntax.isThematicBreak("* ".repeat(50_000)))
        assertTrue(LinearSyntax.isRunOf("- ".repeat(50_000), 0, '-', 3))
    }

    private companion object {
        const val SAMPLES = 200_000
    }
}
