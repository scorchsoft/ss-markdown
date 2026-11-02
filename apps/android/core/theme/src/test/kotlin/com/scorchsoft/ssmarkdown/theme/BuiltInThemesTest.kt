package com.scorchsoft.ssmarkdown.theme

import com.scorchsoft.ssmarkdown.theme.contrast.ContrastReport
import com.scorchsoft.ssmarkdown.theme.contrast.WcagLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInThemesTest {
    @Test
    fun `every built-in passes AA in both variants`() {
        BuiltInThemes.ALL.forEach { theme ->
            val report = ContrastReport.of(theme, WcagLevel.AA)
            assertTrue("${theme.name}: ${report.failures.map { it.message() }}", report.passes)
        }
    }

    @Test
    fun `Contrast passes AAA in both variants`() {
        val report = ContrastReport.of(BuiltInThemes.CONTRAST, WcagLevel.AAA)
        assertTrue(report.failures.joinToString { it.message() }, report.passes)
    }

    @Test
    fun `Clean is the default at 15 px, as the text size model defines 100 percent`() {
        assertSame(BuiltInThemes.CLEAN, BuiltInThemes.DEFAULT)
        assertEquals(15.0, BuiltInThemes.DEFAULT.size.base, 0.0)
        assertEquals(1.6, BuiltInThemes.DEFAULT.size.lineHeight, 0.0)
        assertEquals(18.0, BuiltInThemes.DEFAULT.size.pxAt(120), 1e-9)
    }

    @Test
    fun `ids are unique and look-up works`() {
        assertEquals(listOf("clean", "reader", "compact", "contrast"), BuiltInThemes.ALL.map { it.id })
        assertSame(BuiltInThemes.READER, BuiltInThemes.byId("reader"))
        assertNull(BuiltInThemes.byId("midnight"))
        BuiltInThemes.ALL.forEach { assertNull(it.base) }
    }

    @Test
    fun `Reader is serif and sepia`() {
        assertEquals("Literata", BuiltInThemes.READER.fonts.body)
        assertEquals("#FBF7EF", BuiltInThemes.READER.light.bg.hex)
    }
}
