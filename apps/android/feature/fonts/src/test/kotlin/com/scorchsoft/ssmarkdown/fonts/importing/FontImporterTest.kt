package com.scorchsoft.ssmarkdown.fonts.importing

import com.scorchsoft.ssmarkdown.fonts.Clock
import com.scorchsoft.ssmarkdown.fonts.TestFonts
import com.scorchsoft.ssmarkdown.fonts.store.FontStore
import com.scorchsoft.ssmarkdown.fonts.store.InstalledSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException

class FontImporterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store by lazy { FontStore(temp.newFolder("fonts")) }

    private fun importer(maxBytes: Long = 1024 * 1024) =
        FontImporter(store, Clock { 42 }, Dispatchers.Unconfined, maxBytes)

    @Test
    fun `a TrueType font is named from its own name table, with weight and slope`() =
        runTest {
            val bytes = TestFonts.ttf("Comic Neue", weight = 700, italic = true)
            val result = importer().import("cn-bold-v2.ttf") { bytes.inputStream() }
            val font = (result as ImportResult.Imported).font
            assertEquals("Comic Neue", font.family)
            assertEquals(InstalledSource.IMPORTED, font.source)
            assertEquals(
                "700i.ttf",
                font.files
                    .single()
                    .file.name,
            )
            assertEquals(bytes.size.toLong(), font.bytes)
            assertEquals(font, store.find("comic neue"))
        }

    @Test
    fun `an OpenType CFF font keeps the otf extension, and a nameless font takes the file name`() =
        runTest {
            val bytes = TestFonts.ttf(null, signature = 0x4F54544F)
            val font = (importer().import("My Face.otf") { bytes.inputStream() } as ImportResult.Imported).font
            assertEquals("My Face", font.family)
            assertEquals(
                "400.otf",
                font.files
                    .single()
                    .file.name,
            )
        }

    @Test
    fun `files are judged by their bytes, not their names`() =
        runTest {
            val pdf = "%PDF-1.7 not a font".toByteArray()
            assertEquals(ImportResult.NotAFont("font.ttf"), importer().import("font.ttf") { pdf.inputStream() })
            val woff2 = "wOF2".toByteArray() + ByteArray(40)
            val unsupported = importer().import("x.ttf") { woff2.inputStream() } as ImportResult.Unsupported
            assertEquals(FontKind.WOFF2, unsupported.kind)
            assertTrue(unsupported.message().contains("web fonts"))
            val ttc = "ttcf".toByteArray() + ByteArray(40)
            assertEquals(
                FontKind.COLLECTION,
                (importer().import("x.ttc") { ttc.inputStream() } as ImportResult.Unsupported).kind,
            )
            assertEquals(emptyList<String>(), store.root.list()?.toList())
        }

    @Test
    fun `a damaged table directory is not a font`() =
        runTest {
            val bytes = TestFonts.ttf("X").copyOf(20)
            assertEquals(ImportResult.NotAFont("x.ttf"), importer().import("x.ttf") { bytes.inputStream() })
        }

    @Test
    fun `oversized and unreadable sources fail cleanly`() =
        runTest {
            val big = TestFonts.ttf("Big") + ByteArray(2048)
            assertEquals(
                ImportResult.TooLarge("big.ttf", 1024),
                importer(maxBytes = 1024).import("big.ttf") { big.inputStream() },
            )
            val gone = importer().import("gone.ttf") { throw FileNotFoundException("revoked") }
            assertEquals(ImportResult.Unreadable("gone.ttf"), gone)
            assertEquals("Couldn't read gone.ttf. Try choosing it again.", (gone as ImportResult.Failure).message())
            assertEquals(emptyList<String>(), store.root.list()?.toList())
        }

    @Test
    fun `sniffer recognises every signature`() {
        fun header(text: String) = text.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 5)
        assertEquals(FontKind.TRUETYPE, FontFileSniffer.sniff(byteArrayOf(0, 1, 0, 0, 0, 9)))
        assertEquals(FontKind.TRUETYPE, FontFileSniffer.sniff(header("true")))
        assertEquals(FontKind.OPENTYPE, FontFileSniffer.sniff(header("OTTO")))
        assertEquals(FontKind.WOFF, FontFileSniffer.sniff(header("wOFF")))
        assertEquals(FontKind.UNKNOWN, FontFileSniffer.sniff(byteArrayOf(0, 1, 0, 0, 0, 0)))
        assertEquals(FontKind.UNKNOWN, FontFileSniffer.sniff(byteArrayOf(0, 1)))
    }
}
