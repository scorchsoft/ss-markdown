package com.scorchsoft.ssmarkdown.history.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class CorruptionAndCrashTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val doc = DocumentKey("file:///notes/plan.md")

    @Test
    fun `a corrupt delta loses only the versions between it and the keyframe below`() {
        repeat(Soak.cases(CASES)) { seed ->
            val random = Random(seed)
            val test = TestHistory(folder.newFolder())
            val texts = recordMany(test, random, random.nextInt(20, 120))
            val history = (DocumentHistory.load(test.documentDirectory(doc)) as HistoryLoad.Loaded).history
            val branch = history.index.branch(VersionInfo.MAIN_BRANCH)
            val deltas = branch.indices.filter { !branch[it].keyframe }
            if (deltas.isEmpty()) return@repeat
            val bad = deltas.random(random)
            val pack = File(test.documentDirectory(doc), history.index.packName)
            corruptByte(pack, branch[bad].offset + random.nextLong(0, branch[bad].recordBytes.toLong()))
            val keyframeBelow = (bad - 1 downTo 0).firstOrNull { branch[it].keyframe } ?: -1
            val lost = (keyframeBelow + 1..bad).map { branch[it].id }.toSet()

            test.reopen()
            texts.forEach { (id, text) ->
                val result = test.store.read(doc, id)
                if (id in lost) {
                    assertTrue("seed $seed $id", result is ReadResult.Corrupt)
                    assertNotNull((result as ReadResult.Corrupt).fallback)
                } else {
                    assertEquals("seed $seed $id", text, (result as ReadResult.Text).text)
                }
            }

            // A rewrite drops exactly the damaged versions, logs them, and everything else survives intact.
            val removable =
                texts.keys
                    .filter { it !in lost && it != texts.keys.last() }
                    .take(1)
                    .toSet()
            val reloaded = (DocumentHistory.load(test.documentDirectory(doc)) as HistoryLoad.Loaded).history
            Pruner(test.store.cleanupLog, test.clock).remove(reloaded, removable, CleanupReason.Cleared)
            test.reopen()
            val log = test.store.cleanupLog.read()
            assertEquals(
                "seed $seed",
                lost.size,
                log.filter { it.reason == CleanupReason.Damaged }.sumOf { it.versionsRemoved },
            )
            assertEquals(
                "seed $seed",
                removable.size,
                log
                    .filter {
                        it.reason == CleanupReason.Cleared
                    }.sumOf { it.versionsRemoved },
            )
            assertEquals(texts.keys - lost - removable, test.versions(doc).map { it.id }.toSet())
            test.versions(doc).forEach { assertEquals(texts[it.id], test.text(doc, it.id)) }
        }
    }

    @Test
    fun `a crash between pack append and index rename leaves the previous history`() {
        val test = TestHistory(folder.newFolder())
        val random = Random(7)
        val before = recordMany(test, random, 30)
        val dir = test.documentDirectory(doc)
        val index = File(dir, DocumentHistory.INDEX_FILE)
        val committed = index.readBytes()
        // The state a crash leaves: the next version's records were appended and synced to the pack,
        // but the index rename never happened, and a torn index.ssv.new is left beside it.
        val pack = File(dir, test.packName())
        val tail = PackRecord(keyframe = true, body = TextHash.bytes("never committed\n")).encode()
        PackFile(pack).append(pack.length(), listOf(tail, tail))
        File(dir, DocumentHistory.INDEX_FILE + ".new").writeBytes(committed.copyOf(committed.size / 2))
        assertTrue(committed.contentEquals(index.readBytes()))

        test.reopen()
        assertEquals(before.keys.toList(), test.versions(doc).map { it.id })
        before.forEach { (id, text) -> assertEquals(text, test.text(doc, id)) }
        val next = requireNotNull(test.record(doc, "after the crash\n"))
        assertEquals("after the crash\n", test.text(doc, next.id))
        before.forEach { (id, text) -> assertEquals(text, test.text(doc, id)) }
        assertEquals(listOf(DocumentHistory.INDEX_FILE, test.packName()), dir.list()?.sorted())
    }

    @Test
    fun `a crash midway through a rewrite leaves an orphan pack that is cleaned up`() {
        val test = TestHistory(folder.newFolder())
        val texts = recordMany(test, Random(3), 40)
        val dir = test.documentDirectory(doc)
        File(dir, VersionIndex.packName(99)).writeBytes(ByteArray(1_000) { it.toByte() })
        test.reopen()
        texts.forEach { (id, text) -> assertEquals(text, test.text(doc, id)) }
        assertEquals(2, dir.list()?.size)
    }

    @Test
    fun `a damaged index is quarantined, never deleted, and recording carries on`() {
        val test = TestHistory(folder.newFolder())
        recordMany(test, Random(4), 5)
        val index = File(test.documentDirectory(doc), DocumentHistory.INDEX_FILE)
        corruptByte(index, index.length() / 2)
        test.reopen()
        assertEquals(HistoryListing.Unreadable, test.store.list(doc))
        val fresh = requireNotNull(test.record(doc, "new start\n"))
        assertEquals("new start\n", test.text(doc, fresh.id))
        val quarantined = File(test.root, "quarantine").listFiles().orEmpty()
        assertEquals(1, quarantined.size)
        assertTrue(File(quarantined.single(), DocumentHistory.INDEX_FILE).isFile)
    }

    @Test
    fun `an index from a newer format is read-only and untouched`() {
        val test = TestHistory(folder.newFolder())
        recordMany(test, Random(5), 3)
        val index = File(test.documentDirectory(doc), DocumentHistory.INDEX_FILE)
        val bytes = index.readBytes().also { it[4] = 9 }
        index.writeBytes(bytes)
        test.reopen()
        assertEquals(HistoryListing.Unsupported(9), test.store.list(doc))
        assertEquals(RecordResult.Unsupported(9), test.store.record(doc, "x\n", VersionKind.Saved))
        assertTrue(bytes.contentEquals(index.readBytes()))
    }

    private fun TestHistory.packName(): String =
        (DocumentHistory.load(documentDirectory(doc)) as HistoryLoad.Loaded).history.index.packName

    private fun recordMany(
        test: TestHistory,
        random: Random,
        count: Int,
        start: String = Texts.document(random, 120),
    ): LinkedHashMap<VersionId, String> {
        val texts = LinkedHashMap<VersionId, String>()
        var text = start
        repeat(count) {
            text = Texts.edit(random, text)
            test.clock.advance(RetentionRules.HOUR)
            test.record(doc, text)?.let { texts[it.id] = text }
        }
        return texts
    }

    private companion object {
        const val CASES = 12
    }
}
