package com.scorchsoft.ssmarkdown.files.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeFileNameTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `traversal and separators never survive`() {
        assertEquals("-files-settings.xml", SafeFileName.of("../files/settings.xml").trimStart('.'))
        assertEquals("a-b-c", SafeFileName.of("a/b\\c"))
        assertEquals(SafeFileName.FALLBACK, SafeFileName.of(".."))
        assertEquals(SafeFileName.FALLBACK, SafeFileName.of("."))
        assertEquals(SafeFileName.FALLBACK, SafeFileName.of(null))
        assertEquals("nul-x", SafeFileName.of("nul\u0000x"))
        assertEquals("hidden", SafeFileName.of(".hidden"))
    }

    @Test
    fun `ordinary names are kept`() {
        assertEquals("Plan for Q3.md", SafeFileName.of("Plan for Q3.md"))
        assertEquals("café ☕.md", SafeFileName.of("café ☕.md"))
    }

    @Test
    fun `long names are cut without splitting a surrogate pair`() {
        val name = "a".repeat(SafeFileName.MAX_CHARS - 1) + "😀" + "tail"
        val cut = SafeFileName.of(name)
        assertTrue(cut.length <= SafeFileName.MAX_CHARS)
        assertTrue(!Character.isHighSurrogate(cut.last()))
    }

    @Test
    fun `inside always lands directly in the directory`() {
        val dir = tmp.newFolder("exports")
        val file = SafeFileName.inside(dir, "../../etc/passwd")
        assertEquals(dir.canonicalFile, file.canonicalFile.parentFile)
    }
}
