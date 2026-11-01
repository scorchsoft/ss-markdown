package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class JournalRoundTripTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `random edits replay to the reference text`() {
        repeat(CASES) { seed ->
            val random = Random(seed)
            val baseText = Hostile.text(random, maxPieces = 50)
            val base = fingerprintOf(baseText)
            val test = TestStore(folder.newFolder())
            val writer = test.start("content://doc/$seed", base)
            var reference = baseText
            repeat(random.nextInt(MAX_EDITS) + 1) {
                val edit = Hostile.edit(random, reference)
                writer.append(edit)
                reference = reference.applying(edit)
                when (random.nextInt(ACTIONS)) {
                    0 -> writer.mark(MarkerKind.Checkpoint, "auto · ${random.nextInt()} 😀")
                    1 -> writer.flush(sync = random.nextBoolean())
                    2 -> test.scheduler.advanceBy(random.nextLong(IdleFlushPolicy.DEFAULT_IDLE_MILLIS * 2))
                    3 -> writer.compact(reference)
                }
            }
            writer.close()

            val replayed = test.store.replay("content://doc/$seed", baseText, base)
            replayed as ReplayResult.Ok
            assertEquals("seed $seed", reference, replayed.text)
            assertEquals("seed $seed", writer.lastSeq, replayed.lastSeq)
            assertEquals("seed $seed", 0L, replayed.discardedTailBytes)
        }
    }

    @Test
    fun `a crash keeps everything synced before it`() {
        val baseText = "# Notes\r\n"
        val base = fingerprintOf(baseText)
        val test = TestStore(folder.root)
        val writer = test.start("doc", base)
        writer.append(TextEdit(baseText.length, "", "first 😀"))
        test.scheduler.advanceBy(IdleFlushPolicy.DEFAULT_IDLE_MILLIS)
        writer.append(TextEdit(0, "", "never synced "))
        // The process dies here: no flush, no close.

        val replayed = test.store.replay("doc", baseText, base) as ReplayResult.Ok
        assertEquals("# Notes\r\nfirst 😀", replayed.text)
        assertEquals(1L, replayed.lastSeq)
    }

    @Test
    fun `an empty journal replays to the base`() {
        val base = fingerprintOf("unchanged")
        val test = TestStore(folder.root)
        test.start("doc", base).close()

        assertEquals(ReplayResult.Ok("unchanged", 0, 0, 0), test.store.replay("doc", "unchanged", base))
    }

    @Test
    fun `writes after close are refused`() {
        val test = TestStore(folder.root)
        val writer = test.start("doc", fingerprintOf(""))
        writer.close()

        assertEquals(JournalWriteResult.Closed, writer.append(TextEdit(0, "", "late")))
        assertEquals(JournalWriteResult.Closed, writer.flush())
    }

    private companion object {
        const val CASES = 150
        const val MAX_EDITS = 60
        const val ACTIONS = 12
    }
}
