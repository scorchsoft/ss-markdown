package com.scorchsoft.ssmarkdown.history.journal

import java.io.File
import kotlin.random.Random

/**
 * A journal written one record at a time, remembering where each record
 * ends in the file and what the text was at that point. Torn-tail and
 * bit-flip tests cut and damage the bytes and check replay against these.
 *
 * @property boundaries file length after the header (index 0) and after each record.
 * @property texts the document text at each boundary.
 */
internal class RecordedJournal(
    val bytes: ByteArray,
    val boundaries: List<Long>,
    val texts: List<String>,
    val baseText: String,
    val base: BaseFingerprint,
) {
    /** Replays [damaged] bytes in place of the journal, from a scratch file. */
    fun replay(
        damaged: ByteArray,
        scratch: File,
    ): ReplayResult {
        scratch.writeBytes(damaged)
        return JournalReader(scratch).replay(baseText, base)
    }

    /** The last state whose record ends at or before [cut]. */
    fun stateAt(cut: Long): Int = boundaries.indexOfLast { it <= cut }

    companion object {
        fun write(
            root: File,
            seed: Int,
            records: Int,
        ): RecordedJournal {
            val random = Random(seed)
            val baseText = Hostile.text(random, maxPieces = 30)
            val base = fingerprintOf(baseText)
            val test = TestStore(root)
            val writer = test.start("content://doc/$seed", base)
            val file = test.store.fileFor("content://doc/$seed")
            val boundaries = mutableListOf(file.length())
            val texts = mutableListOf(baseText)
            var text = baseText
            repeat(records) {
                val edit = Hostile.edit(random, text, allowLarge = false)
                writer.append(edit)
                text = text.applying(edit)
                writer.flush(sync = false)
                boundaries += file.length()
                texts += text
            }
            writer.close()
            return RecordedJournal(file.readBytes(), boundaries, texts, baseText, base)
        }
    }
}
