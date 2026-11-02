package com.scorchsoft.ssmarkdown.fonts.bundled

import com.scorchsoft.ssmarkdown.fonts.FontCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class BundledFontsTest {
    private val assets = File("src/main/assets")
    private val manifest = File(assets, BundledFonts.MANIFEST).reader().use { BundledManifest.parse(it) }

    @Test
    fun `the registry and the generated manifest list exactly the same files`() {
        val registry =
            BundledFonts.ALL.flatMap { font ->
                font.files.map { Triple(it.file, font.family, it.italic to it.weights) }
            }
        val generated = manifest.map { Triple(it.file, it.family, it.italic to it.weights) }
        assertEquals(registry.sortedBy { it.first }, generated.sortedBy { it.first })
    }

    @Test
    fun `families with a Reserved Font Name are shipped unmodified`() {
        val rfn = setOf("Lora", "IBM Plex Sans")
        manifest.filter { it.family in rfn }.forEach { assertFalse("${it.file} must not be subset", it.subset) }
    }

    @Test
    fun `every family has its OFL text`() {
        BundledFonts.ALL.forEach { font ->
            val licence = File(assets, font.licence)
            assertTrue("missing ${font.licence}", licence.isFile)
            assertTrue(licence.readText().contains("SIL Open Font License"))
        }
    }

    @Test
    fun `bundled binaries, when present, match the manifest byte for byte`() {
        manifest.forEach { entry ->
            val file = File(assets, "${BundledFonts.ASSET_DIR}/${entry.file}")
            if (file.isFile) {
                assertEquals(entry.file, entry.bytes, file.length())
                val sha =
                    MessageDigest
                        .getInstance(
                            "SHA-256",
                        ).digest(file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                assertEquals(entry.file, entry.sha256, sha)
            }
        }
    }

    @Test
    fun `look-up and fallbacks`() {
        assertSame(BundledFonts.LORA, BundledFonts.byFamily(" lora "))
        assertNull(BundledFonts.byFamily("Comic Sans"))
        assertSame(BundledFonts.SOURCE_SERIF_4, BundledFonts.fallbackFor(FontCategory.SERIF))
        assertSame(BundledFonts.JETBRAINS_MONO, BundledFonts.fallbackFor(FontCategory.MONO))
        assertSame(BundledFonts.INTER, BundledFonts.fallbackFor(FontCategory.HANDWRITING))
        assertSame(BundledFonts.INTER, BundledFonts.fallbackFor(null))
        assertEquals(
            8,
            BundledFonts.ALL
                .map { it.family }
                .distinct()
                .size,
        )
    }

    @Test
    fun `malformed manifest rows are skipped`() {
        val rows = BundledManifest.parse("# c\nx.ttf\tX\t0\t400\n\ny.ttf\tY\t1\t400\t700\t10\tabc\tno\n".reader())
        assertEquals(listOf("y.ttf"), rows.map { it.file })
        assertTrue(rows.single().italic)
    }
}
