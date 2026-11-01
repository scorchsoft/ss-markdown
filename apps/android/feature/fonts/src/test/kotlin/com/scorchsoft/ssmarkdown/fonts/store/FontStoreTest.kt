package com.scorchsoft.ssmarkdown.fonts.store

import com.scorchsoft.ssmarkdown.fonts.FontStyleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FontStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store by lazy { FontStore(temp.newFolder("fonts")) }

    private fun install(
        family: String,
        source: InstalledSource = InstalledSource.GOOGLE,
        at: Long = 1,
    ): InstalledFont {
        val staging = store.stage()
        val file = File(staging, "400.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val font =
            InstalledFont(family, source, at, listOf(InstalledFile(file, FontStyleKey(400, false), 3, Sha256.of(file))))
        return store.commit(staging, font)
    }

    @Test
    fun `commit moves a complete family into place and the index reads back`() {
        val font = install("Lora")
        assertEquals(File(store.root, "google-lora/400.ttf"), font.files.single().file)
        assertEquals(listOf(font), store.installed())
        assertEquals(3L, store.totalBytes())
        assertTrue(store.verify(font))
    }

    @Test
    fun `verify catches a tampered file`() {
        val font = install("Lora")
        font.files
            .single()
            .file
            .writeBytes(byteArrayOf(9, 9, 9))
        assertFalse(store.verify(font))
    }

    @Test
    fun `find prefers the newest source and delete removes both`() {
        install("Lora", InstalledSource.GOOGLE, at = 1)
        install("Lora", InstalledSource.IMPORTED, at = 2)
        assertEquals(InstalledSource.IMPORTED, store.find("LORA")?.source)
        assertTrue(store.delete("lora"))
        assertNull(store.find("Lora"))
        assertFalse(store.delete("lora"))
    }

    @Test
    fun `staging leftovers are invisible and swept`() {
        store.stage()
        assertEquals(emptyList<InstalledFont>(), store.installed())
        store.sweepStaging()
        assertEquals(emptyList<String>(), store.root.list()?.toList())
    }

    @Test
    fun `a damaged index hides the directory instead of crashing`() {
        File(store.root, "google-broken").mkdirs()
        File(store.root, "google-broken/font.tsv").writeText("family\tBroken\nfile\t../../etc/passwd\t400\t0\t1\tx\n")
        assertEquals(emptyList<InstalledFont>(), store.installed())
    }
}
