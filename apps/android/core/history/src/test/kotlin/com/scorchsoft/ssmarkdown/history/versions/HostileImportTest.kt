package com.scorchsoft.ssmarkdown.history.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A crafted history zip is refused as Invalid; it never crashes or exhausts memory (SEC-4). */
class HostileImportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val target = DocumentKey("content://docs/a.md")

    @Test
    fun `deep nesting is refused with a value, not a StackOverflowError`() {
        val test = TestHistory(folder.newFolder())
        val deep = """{"format":"ss-markdown-history","formatVersion":1,"documents":""" + "[".repeat(200_000)
        val zip = zipOf("deep.zip", "manifest.json" to deep)

        assertTrue(test.store.listExport(zip) is ExportContents.Invalid)
        assertTrue(test.store.import(zip, target) is ImportResult.Invalid)
    }

    @Test
    fun `nesting just inside the limit still parses`() {
        val inside = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)
        val outside = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1)

        Json.parse(inside)
        assertTrue(runCatching { Json.parse(outside) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a declared length beyond the cap is refused before any text is read`() {
        val test = TestHistory(folder.newFolder())
        val sha = "0".repeat(64)
        val manifest =
            """{"format":"ss-markdown-history","formatVersion":1,"documents":[{"uri":"content://docs/a.md",""" +
                """"branches":[],"versions":[{"seq":1,"branch":0,"time":1,"kind":"Opened","added":0,"removed":0,""" +
                """"length":${Int.MAX_VALUE},"sha256":"$sha","path":"d0/v1.txt"}]}]}"""
        val zip = zipOf("bomb.zip", "manifest.json" to manifest, "d0/v1.txt" to "x")

        val result = test.store.import(zip, target)

        assertEquals(ImportResult.Invalid("bad length"), result)
    }

    @Test
    fun `R2-DS-7 a zip that fails verification never takes the store's lock`() {
        var locked = 0
        val importer =
            HistoryImporter(HistoryDirectory(folder.newFolder())) {
                locked++
                it()
            }
        val zip = zipOf("bad.zip", "manifest.json" to manifestOf(count = 3, length = 1), "d0/v1.txt" to "x")

        val result = importer.import(zip, target, null)

        assertTrue(result.toString(), result is ImportResult.Invalid)
        assertEquals("verification runs outside the lock", 0, locked)
    }

    @Test
    fun `R2-DS-7 a good zip is verified first, then written under the lock once`() {
        val source = TestHistory(folder.newFolder())
        source.record(target, "one")
        source.record(target, "two")
        val zip = File(folder.root, "good.zip")
        source.store.export(listOf(target), zip)
        var locked = 0

        val result =
            HistoryImporter(HistoryDirectory(folder.newFolder())) {
                locked++
                it()
            }.import(zip, target, null)

        assertTrue(result.toString(), result is ImportResult.Imported)
        assertEquals(1, locked)
    }

    @Test
    fun `R2-DS-7 versions that together declare more than the cap are refused before any text is read`() {
        val test = TestHistory(folder.newFolder())
        // Every version names the same small, highly compressible entry: 17 x 64 Mi chars declared.
        val manifest = manifestOf(count = 17, length = 64 * 1024 * 1024)
        val zip = zipOf("bomb.zip", "manifest.json" to manifest, "d0/v1.txt" to "x")

        val result = test.store.import(zip, target)

        assertEquals(ImportResult.Invalid("the export's texts are too large together"), result)
    }

    /** [count] versions of [length] declared chars, all naming `d0/v1.txt`, with a hash nothing matches. */
    private fun manifestOf(
        count: Int,
        length: Int,
    ): String {
        val sha = "0".repeat(64)
        val versions =
            (1..count).joinToString(",") { seq ->
                """{"seq":$seq,"branch":0,"time":1,"kind":"Opened","added":0,"removed":0,""" +
                    """"length":$length,"sha256":"$sha","path":"d0/v1.txt"}"""
            }
        return """{"format":"ss-markdown-history","formatVersion":1,"documents":[{"uri":"content://docs/a.md",""" +
            """"branches":[],"versions":[$versions]}]}"""
    }

    private fun zipOf(
        name: String,
        vararg entries: Pair<String, String>,
    ): File {
        val file = File(folder.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, text) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }
}
