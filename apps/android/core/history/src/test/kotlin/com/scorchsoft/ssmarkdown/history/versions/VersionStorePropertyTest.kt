package com.scorchsoft.ssmarkdown.history.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class VersionStorePropertyTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val doc = DocumentKey("content://docs/tree/primary%3Anotes/document/primary%3Anotes%2Fhandbook.md")

    @Test
    fun `random edit sequences restore byte-identical, before and after pruning`() {
        repeat(Soak.cases(CASES)) { seed ->
            val random = Random(seed)
            val test = TestHistory(folder.newFolder())
            val expected = LinkedHashMap<VersionId, String>()
            var text = Texts.document(random, random.nextInt(0, 80))
            repeat(random.nextInt(1, MAX_VERSIONS)) {
                text = Texts.edit(random, text)
                test.clock.advance(random.nextLong(1, 6 * RetentionRules.HOUR))
                val kind = VersionKind.entries[random.nextInt(VersionKind.entries.size)]
                test.record(doc, text, kind)?.let { expected[it.id] = text }
                if (random.nextInt(10) == 0) test.store.setPinned(doc, expected.keys.random(random), true, "keep")
            }
            assertAllRestore(test, expected, "seed $seed")
            assertChainsBounded(test)

            test.clock.advance(random.nextLong(0, 200 * RetentionRules.DAY))
            test.reopen(HistoryPolicy(perFileLimit = StorageLimit.Bytes(random.nextLong(0, 20_000))))
            test.store.enforceRetention()
            val survivors = test.versions(doc).map { it.id }.toSet()
            assertTrue("seed $seed: latest kept", expected.keys.last() in survivors)
            assertAllRestore(test, expected.filterKeys { it in survivors }, "seed $seed after pruning")
            assertChainsBounded(test)
        }
    }

    @Test
    fun `the keyframe rule caps every chain at fifty and payload at twice the text`() {
        val test = TestHistory(folder.newFolder())
        var text = Texts.document(Random(1), 400)
        val random = Random(2)
        repeat(300) {
            text = Texts.edit(random, text).let { if (it.length < 1_000) text + "x\n" else it }
            test.record(doc, text)
        }
        assertChainsBounded(test)
        val keyframes = test.versions(doc).count { it.keyframe }
        assertTrue("some deltas and some keyframes: $keyframes", keyframes in 2..100)
    }

    @Test
    fun `an unchanged text records nothing and an Opened updates last opened`() {
        val test = TestHistory(folder.newFolder())
        val first = requireNotNull(test.record(doc, "same\n", VersionKind.Opened))
        test.clock.advance(RetentionRules.DAY)
        assertEquals(RecordResult.Unchanged(first.id), test.store.record(doc, "same\n", VersionKind.Opened))
        assertEquals(
            test.clock.now,
            test.store
                .usage()
                .files
                .single()
                .lastOpenedMillis,
        )
    }

    @Test
    fun `sequence numbers keep order when the wall clock goes backwards`() {
        val test = TestHistory(folder.newFolder())
        val a = requireNotNull(test.record(doc, "a\n"))
        test.clock.advance(-RetentionRules.WEEK)
        val b = requireNotNull(test.record(doc, "b\n"))
        assertTrue(b.id.seq > a.id.seq)
        assertEquals(listOf(a.id, b.id), test.versions(doc).map { it.id })
        assertEquals(a.id, b.parent)
        assertEquals(LineChangeSummary(added = 1, removed = 1), b.changes)
    }

    private fun assertAllRestore(
        test: TestHistory,
        expected: Map<VersionId, String>,
        message: String,
    ) {
        test.reopen()
        assertEquals(message, expected.keys.toList(), test.versions(doc).map { it.id })
        expected.forEach { (id, text) -> assertEquals("$message, $id", text, test.text(doc, id)) }
    }

    private fun assertChainsBounded(test: TestHistory) {
        val versions = test.versions(doc)
        assertTrue(versions.last().keyframe)
        var chain = 0
        var payload = 0L
        for (version in versions.reversed()) {
            if (version.keyframe) {
                chain = 0
                payload = 0
            } else {
                chain++
                payload += version.storedBytes
                assertTrue("chain $chain", chain < KeyframeRule.MAX_CHAIN_LENGTH)
                assertTrue("payload $payload for ${version.textLength}", payload <= 2L * version.textLength)
            }
        }
    }

    private companion object {
        const val CASES = 30
        const val MAX_VERSIONS = 120
    }
}
