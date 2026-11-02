package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FlushPolicyTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val policy = IdleFlushPolicy()

    @Test
    fun `idle policy waits a second, caps latency and syncs big pastes at once`() {
        assertEquals(1_000L, policy.flushDelayMillis(PendingWrites(records = 1, bytes = 30, millisSinceFirst = 0)))
        assertEquals(400L, policy.flushDelayMillis(PendingWrites(records = 4, bytes = 90, millisSinceFirst = 600)))
        assertEquals(0L, policy.flushDelayMillis(PendingWrites(records = 9, bytes = 100, millisSinceFirst = 1_000)))
        assertEquals(0L, policy.flushDelayMillis(PendingWrites(records = 1, bytes = 256L * 1024, millisSinceFirst = 0)))
        val relaxed = IdleFlushPolicy(maxLatencyMillis = 5_000)
        assertEquals(400L, relaxed.flushDelayMillis(PendingWrites(records = 40, bytes = 900, millisSinceFirst = 4_600)))
        assertEquals(1_000L, relaxed.flushDelayMillis(PendingWrites(records = 4, bytes = 90, millisSinceFirst = 600)))
    }

    @Test
    fun `typing never syncs per keystroke, and a pause makes it durable`() {
        // The idle rule alone: a latency cap far beyond this burst of typing.
        val test = TestStore(folder.root, IdleFlushPolicy(maxLatencyMillis = 60_000))
        val writer = test.start("doc", fingerprintOf(""))
        repeat(KEYSTROKES) {
            writer.append(TextEdit(it, "", "k"))
            test.scheduler.advanceBy(KEY_GAP_MILLIS)
        }
        assertEquals(0, test.sinks.forces)
        assertEquals(0L, writer.durableSeq)

        test.scheduler.advanceBy(IdleFlushPolicy.DEFAULT_IDLE_MILLIS - KEY_GAP_MILLIS - 1)
        assertEquals(0, test.sinks.forces)
        test.scheduler.advanceBy(1)
        assertEquals(1, test.sinks.forces)
        assertEquals(KEYSTROKES.toLong(), writer.durableSeq)
        assertEquals(0, test.scheduler.pending)
    }

    @Test
    fun `typing without a pause is still synced every five seconds when the cap says so`() {
        val test = TestStore(folder.root, IdleFlushPolicy(maxLatencyMillis = 5_000))
        val writer = test.start("doc", fingerprintOf(""))
        val gap = 200L
        repeat((12_000 / gap).toInt()) {
            writer.append(TextEdit(it, "", "k"))
            test.scheduler.advanceBy(gap)
        }
        assertEquals(2, test.sinks.forces)
    }

    @Test
    fun `by default typing without a pause is synced every second, so a kill loses at most one`() {
        val test = TestStore(folder.root)
        val writer = test.start("doc", fingerprintOf(""))
        val gap = 200L
        var oldestUnsynced: Long? = null
        repeat((12_000 / gap).toInt()) {
            if (oldestUnsynced == null) oldestUnsynced = test.clock.now
            writer.append(TextEdit(it, "", "k"))
            test.scheduler.advanceBy(gap)
            if (writer.durableSeq == writer.lastSeq) oldestUnsynced = null
            val waited = oldestUnsynced?.let { test.clock.now - it } ?: 0
            assertTrue("waited $waited ms", waited < IdleFlushPolicy.DEFAULT_MAX_LATENCY_MILLIS)
        }
        assertEquals(12, test.sinks.forces)
    }

    @Test
    fun `flush on demand syncs now and only when something is pending`() {
        val test = TestStore(folder.root)
        val writer = test.start("doc", fingerprintOf(""))
        writer.append(TextEdit(0, "", "save me"))

        assertEquals(JournalWriteResult.Written(1), writer.flush())
        assertEquals(1, test.sinks.forces)
        assertEquals(0, test.scheduler.pending)
        writer.flush()
        assertEquals(1, test.sinks.forces)
    }

    @Test
    fun `flush without sync writes but does not fsync`() {
        val test = TestStore(folder.root)
        val writer = test.start("doc", fingerprintOf(""))
        writer.append(TextEdit(0, "", "x"))
        writer.flush(sync = false)

        assertEquals(1, test.sinks.writes)
        assertEquals(0, test.sinks.forces)
        assertEquals(1, test.scheduler.pending)
    }

    @Test
    fun `a large paste is synced straight away`() {
        val test = TestStore(folder.root)
        val writer = test.start("doc", fingerprintOf(""))
        writer.append(TextEdit(0, "", "p".repeat(300 * 1024)))

        assertEquals(1, test.sinks.forces)
        assertEquals(1L, writer.durableSeq)
    }

    @Test
    fun `a failed write is cut back and retried, so later records stay reachable`() {
        val test = TestStore(folder.root)
        val writer = test.start("doc", fingerprintOf(""))
        writer.append(TextEdit(0, "", "one"))
        test.sinks.failAfterBytes = 7

        assertTrue(writer.flush() is JournalWriteResult.Failed)
        assertTrue(writer.lastFailure != null)
        writer.append(TextEdit(3, "", " two"))
        assertEquals(JournalWriteResult.Written(2), writer.flush())
        assertNull(writer.lastFailure)
        writer.close()

        assertEquals("one two", (test.store.replay("doc", "", fingerprintOf("")) as ReplayResult.Ok).text)
    }

    @Test
    fun `when even the cut back fails, compaction repairs the journal`() {
        val test = TestStore(folder.root)
        val writer = test.start("doc", fingerprintOf(""))
        writer.append(TextEdit(0, "", "one"))
        test.sinks.failAfterBytes = 7
        test.sinks.failTruncate = true

        assertTrue(writer.flush() is JournalWriteResult.Failed)
        assertTrue("no appending after a fragment", writer.flush() is JournalWriteResult.Failed)
        test.sinks.failTruncate = false
        assertTrue(writer.compact("one") is JournalWriteResult.Written)
        writer.append(TextEdit(3, "", " two"))
        writer.close()

        assertEquals("one two", (test.store.replay("doc", "", fingerprintOf("")) as ReplayResult.Ok).text)
    }

    private companion object {
        const val KEYSTROKES = 30
        const val KEY_GAP_MILLIS = 100L
    }
}
