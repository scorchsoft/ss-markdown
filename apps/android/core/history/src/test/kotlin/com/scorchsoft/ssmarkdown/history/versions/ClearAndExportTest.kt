package com.scorchsoft.ssmarkdown.history.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ClearAndExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val a = DocumentKey("file:///a.md")
    private val b = DocumentKey("file:///b.md")

    @Test
    fun `every clear previews exactly what it then removes, keeping pins and skipped files`() {
        val requests =
            listOf(
                ClearRequest.NotOpenedFor(30),
                ClearRequest.OlderThan(5),
                ClearRequest.OneFile(a),
                ClearRequest.Everything(),
                ClearRequest.Everything(includePinned = true),
            )
        for (request in requests) {
            for (skip in listOf(emptySet(), setOf(b))) {
                val test = populated()
                val before = test.versions(a).size + test.versions(b).size
                val pinned = pinnedIds(test)
                val preview = test.store.previewClear(request, skip)
                val result = test.store.clear(request, skip) as ClearResult.Cleared
                val after = test.versions(a).size + test.versions(b).size
                assertEquals("$request", preview.versions, result.versions)
                assertEquals("$request", preview.versions, before - after)
                assertEquals("$request", preview.files, result.files)
                if (request != ClearRequest.Everything(includePinned = true)) {
                    assertEquals("$request keeps pins", pinned, pinnedIds(test))
                }
                if (b in skip) assertEquals(BEFORE_B, test.versions(b).size)
                assertEquals(
                    result.versions,
                    test.store.cleanupLog
                        .read()
                        .sumOf { it.versionsRemoved },
                )
            }
        }
    }

    @Test
    fun `older-than keeps every file's latest version and its pins`() {
        val test = populated()
        test.store.clear(ClearRequest.OlderThan(0))
        listOf(a, b).forEach { key ->
            val left = test.versions(key)
            assertTrue(left.all { it.pinned || it == left.last() })
            assertEquals(2, left.size)
        }
    }

    @Test
    fun `export first writes a verified zip of exactly the cleared versions before deleting`() {
        val test = populated()
        val texts = allTexts(test, a)
        val zip = File(folder.root, "export.zip")
        val result = test.store.clear(ClearRequest.OneFile(a), exportFirst = zip) as ClearResult.Cleared
        val export = requireNotNull(result.export)
        assertEquals(result.versions, export.versions)
        ZipFile(zip).use { file ->
            val manifest = ExportManifest.read(file)
            manifest.documents.single().versions.forEach {
                assertEquals(texts[it.info.id], ExportManifest.readText(file, it))
            }
        }
        assertFalse(File(zip.path + ".part").exists())
    }

    @Test
    fun `a failed export deletes nothing`() {
        val test = populated()
        val before = test.versions(a)
        val blocker = folder.newFile("not-a-dir")
        val result = test.store.clear(ClearRequest.Everything(), exportFirst = File(blocker, "x.zip"))
        assertTrue(result is ClearResult.ExportFailed)
        assertEquals(before, test.versions(a))
    }

    @Test
    fun `export then import attaches a separate branch and never touches the current history`() {
        val test = populated()
        val texts = allTexts(test, a)
        val zip = File(folder.root, "a.zip")
        assertTrue(test.store.export(listOf(a), zip) is ExportResult.Exported)

        // Same document, since changed: the import is a new branch beside the untouched main line.
        test.record(a, "changed since the export\n")
        val mainBefore = test.versions(a)
        val imported = test.store.import(zip, a) as ImportResult.Imported
        assertEquals(texts.size, imported.versions)
        val all = test.versions(a)
        assertEquals(mainBefore, all.filter { it.branch == VersionInfo.MAIN_BRANCH })
        val branch = all.filter { it.branch == imported.branches.single() }
        assertEquals(texts.values.toList(), branch.map { test.text(a, it.id) })
        assertEquals(mainBefore.last().id, all.filter { it.branch == 0 }.last().id)
        assertEquals(branch.map { it.pinned }, texts.keys.map { id -> mainBefore.first { it.id == id }.pinned })

        // Another device: a fresh store with no history for the file.
        val other = TestHistory(folder.newFolder())
        val c = DocumentKey("content://other/a.md")
        assertTrue(other.store.import(zip, c) is ImportResult.Imported)
        assertEquals(texts.values.toList(), other.versions(c).map { other.text(c, it.id) })
        assertEquals(other.versions(c).first().id, other.versions(c)[1].parent)
    }

    @Test
    fun `a tampered or newer export imports nothing`() {
        val test = populated()
        val zip = File(folder.root, "a.zip")
        test.store.export(listOf(a), zip)
        val tampered =
            rewriteZip(zip, "tampered.zip") { name, bytes ->
                if (name.endsWith(".txt") && name.contains("/v1.")) bytes + 'x'.code.toByte() else bytes
            }
        val before = test.versions(a)
        assertTrue(test.store.import(tampered, a) is ImportResult.Invalid)
        assertEquals(before, test.versions(a))
        val newer =
            rewriteZip(zip, "newer.zip") { name, bytes ->
                if (name ==
                    "manifest.json"
                ) {
                    String(bytes).replace("\"formatVersion\":1", "\"formatVersion\":9").toByteArray()
                } else {
                    bytes
                }
            }
        assertEquals(ImportResult.Unsupported(9), test.store.import(newer, a))
        assertTrue(test.store.import(folder.newFile("empty.zip"), a) !is ImportResult.Imported)
    }

    @Test
    fun `history off records nothing and can keep, delete or export existing history`() {
        val keep = populated()
        assertEquals(ClearResult.Cleared(0, 0, 0, null), keep.store.turnOff(ExistingHistory.Keep))
        assertEquals(RecordResult.Off, keep.store.record(a, "new\n", VersionKind.Saved))
        assertEquals(BEFORE_B, keep.versions(b).size)

        val delete = populated()
        delete.store.turnOff(ExistingHistory.Delete)
        assertEquals(HistoryListing.Empty, delete.store.list(a))
        assertTrue(
            delete.store.cleanupLog
                .read()
                .all { it.reason == CleanupReason.HistoryOff },
        )

        val export = populated()
        val zip = File(folder.root, "off.zip")
        val result = export.store.turnOff(ExistingHistory.ExportThenDelete(zip)) as ClearResult.Cleared
        assertEquals(2, result.export?.documents)
        assertEquals(
            0,
            export.store
                .usage()
                .files.size,
        )
    }

    @Test
    fun `usage matches the bytes on disk and stays near the live size`() {
        val test = TestHistory(folder.newFolder())
        val random = Random(5)
        var text = Texts.document(random, 500)
        repeat(150) {
            text = Texts.edit(random, text)
            test.record(a, text)
        }
        val usage = test.store.usage()
        assertEquals(test.diskBytes(), usage.totalBytes)
        val history = (DocumentHistory.load(test.documentDirectory(a)) as HistoryLoad.Loaded).history
        assertTrue(history.index.packLength <= 2 * history.index.liveBytes + 1)
        assertEquals(history.diskBytes(), usage.files.single().bytes)
        assertEquals(test.versions(a).size, usage.files.single().versions)
    }

    @Test
    fun `save as copies history, a moved file is found by fingerprint`() {
        val test = populated()
        val copy = DocumentKey("file:///copy-of-a.md")
        assertEquals(true, test.store.copyHistory(a, copy).getOrNull())
        assertEquals(allTexts(test, a).values.toList(), allTexts(test, copy).values.toList())
        assertTrue(test.store.copyHistory(a, copy).isFailure)
        val head = test.versions(a).last()
        assertEquals(setOf(a, copy), test.store.findByFingerprint(head.sha256Hex).toSet())
    }

    private fun populated(): TestHistory {
        val test = TestHistory(folder.newFolder())
        val random = Random(11)
        var textA = Texts.document(random, 50)
        var textB = Texts.document(random, 50)
        repeat(BEFORE_A) {
            textA = Texts.edit(random, textA) + "a$it\n"
            test.clock.advance(RetentionRules.DAY)
            test.record(a, textA, if (it == 0) VersionKind.Opened else VersionKind.Saved)
        }
        test.clock.advance(40 * RetentionRules.DAY)
        repeat(BEFORE_B) {
            textB = Texts.edit(random, textB) + "b$it\n"
            test.clock.advance(RetentionRules.DAY)
            test.record(b, textB, if (it == 0) VersionKind.Opened else VersionKind.Saved)
        }
        test.store.setPinned(a, test.versions(a)[2].id, true, "keep")
        test.store.setPinned(b, test.versions(b)[1].id, true)
        return test
    }

    private fun pinnedIds(test: TestHistory) =
        listOf(a, b).flatMap { key ->
            test.versions(key).filter { it.pinned }.map {
                key to
                    it.id
            }
        }

    private fun allTexts(
        test: TestHistory,
        key: DocumentKey,
    ): Map<VersionId, String> = test.versions(key).associate { it.id to requireNotNull(test.text(key, it.id)) }

    private fun rewriteZip(
        source: File,
        name: String,
        transform: (String, ByteArray) -> ByteArray,
    ): File {
        val target = File(folder.root, name)
        ZipFile(source).use { input ->
            ZipOutputStream(target.outputStream()).use { out ->
                input.entries().asSequence().forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(transform(entry.name, input.getInputStream(entry).readBytes()))
                    out.closeEntry()
                }
            }
        }
        return target
    }

    private companion object {
        const val BEFORE_A = 12
        const val BEFORE_B = 8
    }
}
