package com.scorchsoft.ssmarkdown.history.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportContentsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val a = DocumentKey("content://docs/tree/notes%2Fa.md")
    private val b = DocumentKey("file:///b.md")

    @Test
    fun `lists every document in a multi-document export with counts, times and fingerprint`() {
        val test = TestHistory(folder.newFolder())
        test.clock.advance(1_000)
        test.record(a, "one\n", VersionKind.Opened)
        test.clock.advance(1_000)
        val newestA = test.record(a, "one\ntwo\n")
        test.clock.advance(1_000)
        val onlyB = test.record(b, "b\n", VersionKind.Opened)
        val zip = File(folder.root, "both.zip")
        assertTrue(test.store.export(listOf(a, b), zip) is ExportResult.Exported)

        val listed = test.store.listExport(zip) as ExportContents.Listed
        assertEquals(listOf(a.uri, b.uri), listed.documents.map { it.uri })
        val first = listed.documents[0]
        assertEquals("a.md", first.displayName)
        assertEquals(2, first.versions)
        assertEquals(1, first.branches)
        assertTrue(first.oldestMillis < first.newestMillis)
        assertEquals(newestA?.sha256Hex, first.latestSha256Hex)
        assertEquals(onlyB?.sha256Hex, listed.documents[1].latestSha256Hex)
        assertEquals(1, listed.documents[1].versions)
    }

    @Test
    fun `a listed uri imports exactly that document`() {
        val test = TestHistory(folder.newFolder())
        test.record(a, "alpha\n", VersionKind.Opened)
        test.record(b, "beta\n", VersionKind.Opened)
        val zip = File(folder.root, "both.zip")
        test.store.export(listOf(a, b), zip)
        val pick = (test.store.listExport(zip) as ExportContents.Listed).documents.first { it.uri == b.uri }

        val other = TestHistory(folder.newFolder())
        val target = DocumentKey("content://elsewhere/b.md")
        val imported = other.store.import(zip, target, pick.uri) as ImportResult.Imported
        assertEquals(pick.versions, imported.versions)
        assertEquals(pick.latestSha256Hex, other.versions(target).last().sha256Hex)
    }

    @Test
    fun `listing never writes and reports bad, newer and unreadable zips`() {
        val test = TestHistory(folder.newFolder())
        test.record(a, "alpha\n", VersionKind.Opened)
        val before = test.versions(a)

        val notExport = zipOf("other.zip", "manifest.json" to """{"format":"something-else"}""")
        assertTrue(test.store.listExport(notExport) is ExportContents.Invalid)
        val noManifest = zipOf("none.zip", "readme.txt" to "hi")
        assertTrue(test.store.listExport(noManifest) is ExportContents.Invalid)
        val newer = zipOf("newer.zip", "manifest.json" to """{"format":"ss-markdown-history","formatVersion":7}""")
        assertEquals(ExportContents.Unsupported(7), test.store.listExport(newer))
        assertTrue(test.store.listExport(folder.newFile("empty.zip")) !is ExportContents.Listed)
        assertTrue(test.store.listExport(File(folder.root, "missing.zip")) is ExportContents.Failed)
        assertEquals(before, test.versions(a))
    }

    private fun zipOf(
        name: String,
        vararg entries: Pair<String, String>,
    ): File {
        val target = File(folder.root, name)
        ZipOutputStream(target.outputStream()).use { out ->
            for ((path, text) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return target
    }
}
