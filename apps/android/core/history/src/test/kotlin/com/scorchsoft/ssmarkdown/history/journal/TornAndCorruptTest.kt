package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.experimental.xor

class TornAndCorruptTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a journal cut at any byte of its last records replays to the last whole record`() {
        repeat(SEEDS) { seed ->
            val journal = RecordedJournal.write(folder.newFolder(), seed, RECORDS)
            val scratch = folder.newFile()
            val from = journal.boundaries[RECORDS - TORN_RECORDS]
            for (cut in from until journal.bytes.size) {
                val result = journal.replay(journal.bytes.copyOf(cut.toInt()), scratch)
                val state = journal.stateAt(cut)
                val expected =
                    ReplayResult.Ok(
                        journal.texts[state],
                        lastSeq = state.toLong(),
                        recordCount = state,
                        discardedTailBytes = cut - journal.boundaries[state],
                    )
                // The undo history every cut rebuilds is checked in UndoHistoryJournalTest; here, the text.
                val textOnly = (result as? ReplayResult.Ok)?.copy(history = JournalHistory.EMPTY, editsReplayed = false)
                assertEquals("seed $seed cut $cut", expected, textOnly ?: result)
            }
        }
    }

    @Test
    fun `a zero-filled tail is a torn write, not damage`() {
        val journal = RecordedJournal.write(folder.root, seed = 1, records = RECORDS)
        val result = journal.replay(journal.bytes + ByteArray(ZERO_TAIL), folder.newFile()) as ReplayResult.Ok

        assertEquals(journal.texts.last(), result.text)
        assertEquals(ZERO_TAIL.toLong(), result.discardedTailBytes)
    }

    @Test
    fun `a flipped bit in a record is corruption, with the text before it recovered`() {
        val journal = RecordedJournal.write(folder.root, seed = 2, records = RECORDS)
        val scratch = folder.newFile()
        val target = RECORDS / 2
        val start = journal.boundaries[target - 1]
        val end = journal.boundaries[target]
        // Everything after the length field: CRC, type, seq and payload.
        for (index in start + LENGTH_BYTES until end) {
            for (bit in 0 until Byte.SIZE_BITS) {
                val result = journal.replay(flip(journal.bytes, index, bit), scratch)
                assertTrue("byte $index bit $bit gave $result", result is ReplayResult.Corrupt)
                result as ReplayResult.Corrupt
                assertEquals(start, result.offset)
                assertEquals(journal.texts[target - 1], result.recoveredText)
                assertEquals(target - 1L, result.lastSeq)
            }
        }
    }

    @Test
    fun `a flipped bit in a length field never applies anything wrong`() {
        val journal = RecordedJournal.write(folder.root, seed = 3, records = RECORDS)
        val scratch = folder.newFile()
        val target = RECORDS / 2
        val start = journal.boundaries[target - 1]
        for (index in start until start + LENGTH_BYTES) {
            for (bit in 0 until Byte.SIZE_BITS) {
                // A length that now runs past the end is indistinguishable from
                // a torn write (as in LevelDB); either way only whole, checked
                // records before it are applied.
                val text =
                    when (val result = journal.replay(flip(journal.bytes, index, bit), scratch)) {
                        is ReplayResult.Ok -> result.text
                        is ReplayResult.Corrupt -> result.recoveredText
                        else -> error("byte $index bit $bit gave $result")
                    }
                assertEquals(journal.texts[target - 1], text)
            }
        }
    }

    @Test
    fun `a damaged header is never replayed`() {
        val journal = RecordedJournal.write(folder.root, seed = 4, records = 2)
        val scratch = folder.newFile()
        for (index in 0 until journal.boundaries.first()) {
            for (bit in 0 until Byte.SIZE_BITS) {
                val result = journal.replay(flip(journal.bytes, index, bit), scratch)
                assertTrue(
                    "byte $index bit $bit gave $result",
                    result is ReplayResult.Unreadable || result is ReplayResult.Unsupported,
                )
            }
            val cut = journal.replay(journal.bytes.copyOf(index.toInt()), scratch)
            assertTrue("cut at $index gave $cut", cut is ReplayResult.Unreadable)
        }
    }

    @Test
    fun `an edit that does not fit the text is corruption, not garbage`() {
        val base = fingerprintOf("hello")
        val test = TestStore(folder.root)
        val writer = test.start("doc", base)
        writer.append(TextEdit(0, "hello", "howdy"))
        writer.append(TextEdit(0, "hello", "never"))
        writer.close()

        val result = test.store.replay("doc", "hello", base) as ReplayResult.Corrupt
        assertEquals("howdy", result.recoveredText)
        assertEquals(1L, result.lastSeq)
    }

    @Test
    fun `the wrong base is refused before any edit is applied`() {
        val base = fingerprintOf("original")
        val test = TestStore(folder.root)
        test.start("doc", base).apply { append(TextEdit(0, "", ">")) }.close()

        val edited = fingerprintOf("originaL")
        assertEquals(ReplayResult.BaseMismatch(base, edited), test.store.replay("doc", "originaL", edited))
        val longer = fingerprintOf("original!")
        assertTrue(test.store.replay("doc", "original!", longer) is ReplayResult.BaseMismatch)
    }

    @Test
    fun `a different lastModified alone is not a different base`() {
        val test = TestStore(folder.root)
        test.start("doc", fingerprintOf("same", lastModified = 1)).apply { append(TextEdit(4, "", "!")) }.close()

        val result = test.store.replay("doc", "same", fingerprintOf("same", lastModified = 0)) as ReplayResult.Ok
        assertEquals("same!", result.text)
    }

    private fun flip(
        bytes: ByteArray,
        index: Long,
        bit: Int,
    ): ByteArray = bytes.copyOf().also { it[index.toInt()] = it[index.toInt()] xor (1 shl bit).toByte() }

    private companion object {
        const val SEEDS = 6
        const val RECORDS = 10
        const val TORN_RECORDS = 3
        const val LENGTH_BYTES = 4
        const val ZERO_TAIL = 4096
    }
}
