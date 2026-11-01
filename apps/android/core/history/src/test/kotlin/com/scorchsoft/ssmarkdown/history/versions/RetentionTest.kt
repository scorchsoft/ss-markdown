package com.scorchsoft.ssmarkdown.history.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class RetentionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val doc = DocumentKey("file:///notes/journal.md")

    @Test
    fun `the schedule keeps a day of everything, then hourly, daily and weekly`() {
        val now = 400 * RetentionRules.DAY
        val versions = (0 until 20_000).map { i -> info(i.toLong(), now - i * 20 * MINUTE) }.reversed()
        val removed = RetentionRules.thin(versions, now).map { it.id }.toSet()
        val kept = versions.filter { it.id !in removed }

        fun keptBetween(
            from: Long,
            to: Long,
        ) = kept.count { now - it.wallTimeMillis in from until to }
        assertEquals(72, keptBetween(0, RetentionRules.DAY))
        assertTrue(keptBetween(RetentionRules.DAY, RetentionRules.HOURLY_UNTIL) in 143..145)
        assertTrue(keptBetween(RetentionRules.HOURLY_UNTIL, RetentionRules.DAILY_UNTIL) in 82..84)
        assertTrue(keptBetween(RetentionRules.DAILY_UNTIL, 260 * RetentionRules.DAY) in 24..26)
        assertEquals("idempotent", emptyList<VersionInfo>(), RetentionRules.thin(kept, now))
    }

    @Test
    fun `the schedule never drops the latest or a pinned version`() {
        val now = 400 * RetentionRules.DAY
        val versions = (0 until 50).map { info(it.toLong(), it * RetentionRules.HOUR, pinned = it % 7 == 0) }
        val removed = RetentionRules.thin(versions, now)
        assertTrue(removed.none { it.pinned || it == versions.last() })
        assertTrue(removed.isNotEmpty())
    }

    @Test
    fun `limits never touch protected versions, report shortfalls and log every removal`() {
        repeat(Soak.cases(CASES)) { seed ->
            val random = Random(seed)
            val test = TestHistory(folder.newFolder())
            val docs = List(random.nextInt(1, 4)) { DocumentKey("file:///d$it.md") }
            val texts = docs.associateWith { Texts.document(random, 200) }.toMutableMap()
            repeat(random.nextInt(20, 80)) {
                val key = docs.random(random)
                texts[key] = Texts.edit(random, texts.getValue(key))
                test.clock.advance(random.nextLong(1, 3 * RetentionRules.DAY))
                val recorded = test.record(key, texts.getValue(key))
                if (recorded != null && random.nextInt(8) == 0) test.store.setPinned(key, recorded.id, true)
            }
            val before = docs.associateWith { test.versions(it) }
            val limit = random.nextLong(0, 30_000)
            test.store.updatePolicy(
                HistoryPolicy(totalLimit = StorageLimit.Bytes(limit), perFileLimit = StorageLimit.None),
            )
            val now = test.clock.now
            val report = test.store.enforceRetention()

            var removed = 0
            for (key in docs) {
                val after = test.versions(key).map { it.id }.toSet()
                val old = before.getValue(key)
                val protected =
                    old.filter {
                        it.pinned || it == old.lastOrNull() ||
                            now - it.wallTimeMillis < 7 * RetentionRules.DAY
                    }
                assertTrue("seed $seed", protected.all { it.id in after })
                removed += old.size - after.size
                test.versions(key).forEach { assertNotNull(test.text(key, it.id)) }
            }
            assertEquals(
                "seed $seed: log is complete",
                removed,
                test.store.cleanupLog
                    .read()
                    .sumOf { it.versionsRemoved },
            )
            assertEquals(removed, report.removals.sumOf { it.versionsRemoved })
            val used = test.store.usage().totalBytes
            if (used >
                limit
            ) {
                assertTrue("seed $seed: says why it stopped", report.shortfalls.any { it.document == null })
            }
        }
    }

    @Test
    fun `the per-file limit thins oldest first and stops at the seven-day line`() {
        val test = TestHistory(folder.newFolder())
        var text = Texts.document(Random(1), 300)
        val random = Random(2)
        repeat(60) {
            text = Texts.edit(random, text) + "edit $it\n"
            test.clock.advance(6 * RetentionRules.HOUR)
            test.record(doc, text)
        }
        val before = test.versions(doc)
        test.store.updatePolicy(HistoryPolicy(perFileLimit = StorageLimit.Bytes(1)))
        val report = test.store.enforceRetention()
        val after = test.versions(doc)
        val cutoff = test.clock.now - 7 * RetentionRules.DAY
        assertEquals(before.filter { it.wallTimeMillis > cutoff }.map { it.id }, after.map { it.id })
        assertEquals(doc, report.shortfalls.single().document)
        assertTrue(report.limitThinningHappened)
        assertTrue(report.removals.all { it.reason == CleanupReason.FileLimit || it.reason == CleanupReason.Thinned })
    }

    @Test
    fun `the total limit thins files not opened for ninety days first`() {
        val test = TestHistory(folder.newFolder())
        val stale = DocumentKey("file:///stale.md")
        val fresh = DocumentKey("file:///fresh.md")
        val random = Random(9)
        var a = Texts.document(random, 200)
        var b = Texts.document(random, 200)
        repeat(20) {
            a = Texts.edit(random, a) + "a$it\n"
            test.clock.advance(RetentionRules.HOUR)
            test.record(stale, a, VersionKind.Opened)
        }
        test.clock.advance(100 * RetentionRules.DAY)
        repeat(20) {
            b = Texts.edit(random, b) + "b$it\n"
            test.clock.advance(RetentionRules.HOUR)
            test.record(fresh, b, VersionKind.Opened)
        }
        test.clock.advance(10 * RetentionRules.DAY)
        test.store.enforceRetention() // The schedule alone first, so what follows is the limit's doing.
        val freshBefore = test.versions(fresh).size
        val staleBefore = test.versions(stale).size
        val used = test.store.usage().totalBytes
        test.store.updatePolicy(HistoryPolicy(totalLimit = StorageLimit.Bytes(used - 1)))
        test.store.enforceRetention()
        assertEquals(freshBefore, test.versions(fresh).size)
        assertTrue(test.versions(stale).size < staleBefore)
    }

    @Test
    fun `forget unused files removes all but pinned versions`() {
        val test = TestHistory(folder.newFolder())
        val pinned = requireNotNull(test.record(doc, "one\n", VersionKind.Opened))
        test.store.setPinned(doc, pinned.id, true, "Draft 1")
        test.record(doc, "two\n")
        test.clock.advance(31 * RetentionRules.DAY)
        test.store.updatePolicy(HistoryPolicy(forgetUnusedAfterDays = 30))
        val report = test.store.enforceRetention()
        assertEquals(listOf(pinned.id), test.versions(doc).map { it.id })
        assertEquals("Draft 1", test.versions(doc).single().label)
        assertEquals(CleanupReason.UnusedFile, report.removals.single().reason)
    }

    private fun info(
        seq: Long,
        wall: Long,
        pinned: Boolean = false,
    ) = VersionInfo(
        VersionId(seq),
        0,
        wall,
        VersionKind.Saved,
        null,
        pinned,
        null,
        LineChangeSummary.NONE,
        0,
        "",
        0,
        false,
    )

    private companion object {
        const val CASES = 12
        const val MINUTE = 60_000L
    }
}
