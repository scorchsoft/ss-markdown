package com.scorchsoft.ssmarkdown.markdown.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentStyleTest {
    @Test
    fun emptyFileGetsDefaults() = assertEquals(DocumentStyle.DEFAULT, DocumentStyle.detect(""))

    @Test
    fun detectsEachHabit() {
        val text =
            """
            Some _emphasis_ and __strong__ words, plus snake_case_names.

            + one
            + two
            * three

            1) first
            2) second

            ~~~kotlin
            val *notEmphasis* = 1 // `*` in code does not count
            ~~~

            |a|b|
            |-|-|

            	tabbed paragraph
            """.trimIndent()
        assertEquals(
            DocumentStyle(
                emphasis = '_',
                strong = '_',
                bullet = '+',
                orderedDelimiter = ')',
                indentUnit = "\t",
                codeFence = "~~~",
                paddedTables = false,
            ),
            DocumentStyle.detect(text),
        )
    }

    @Test
    fun aTieKeepsTheDefault() {
        val style = DocumentStyle.detect("*a* _b_\n- x\n* y")
        assertEquals('*', style.emphasis)
        assertEquals('-', style.bullet)
    }

    @Test
    fun twoSpaceIndentationIsKept() = assertEquals("  ", DocumentStyle.detect("para\n  indented para\n").indentUnit)

    @Test
    fun onlyTheScannedPrefixCounts() {
        val text = "- a\n" + "x".repeat(100) + "\n+ b\n+ c\n"
        assertEquals('-', DocumentStyle.detect(text, scanChars = 10).bullet)
        assertEquals('+', DocumentStyle.detect(text).bullet)
    }
}
