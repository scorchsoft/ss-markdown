package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LineEndingsTest {
    private fun mixed(vararg exceptions: Pair<Int, LineEnding>): LineEndings =
        LineEndings.of(
            LineEnding.CRLF,
            exceptions.map { it.first }.toIntArray(),
            exceptions.map { it.second }.toTypedArray(),
        )

    @Test
    fun `uniform endings ignore edits`() {
        val uniform = LineEndings.Uniform(LineEnding.CRLF)
        assertSame(uniform, uniform.afterEdit(3, 2, 7))
    }

    @Test
    fun `lines after an edit shift by the change in line count`() {
        val endings = mixed(1 to LineEnding.LF, 5 to LineEnding.CR)
        val shifted = endings.afterEdit(firstLine = 2, removedBreaks = 1, insertedBreaks = 3)
        assertEquals(LineEnding.LF, shifted.endingAfter(1))
        assertEquals(LineEnding.CR, shifted.endingAfter(7))
        assertEquals(LineEnding.CRLF, shifted.endingAfter(5))
    }

    @Test
    fun `removed breaks lose their record and the last exception collapses to uniform`() {
        val endings = mixed(2 to LineEnding.LF)
        assertEquals(
            LineEndings.Uniform(LineEnding.CRLF),
            endings.afterEdit(firstLine = 1, removedBreaks = 2, insertedBreaks = 0),
        )
    }

    @Test
    fun `an edit inside a line keeps that line's break`() {
        val endings = mixed(2 to LineEnding.LF)
        assertEquals(endings, endings.afterEdit(firstLine = 2, removedBreaks = 0, insertedBreaks = 0))
    }
}
