package com.scorchsoft.ssmarkdown.history.journal

import java.io.InputStream

/**
 * An input stream that knows how far it has read, so the scanner can
 * say exactly where a bad record starts.
 */
internal class CountingInput(
    private val input: InputStream,
) {
    var position: Long = 0
        private set

    /** Reads until [dst] is full or the stream ends; returns the bytes read. */
    fun readUpTo(dst: ByteArray): Int {
        var total = 0
        while (total < dst.size) {
            val n = input.read(dst, total, dst.size - total)
            if (n < 0) break
            total += n
        }
        position += total
        return total
    }

    /** Skips [count] bytes, or to the end of the stream. */
    fun skip(count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() < 0) break
                left--
                position++
            } else {
                left -= n
                position += n
            }
        }
    }

    /** Reads to the end of the stream; true when every remaining byte is zero. */
    fun restIsZero(): Boolean {
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) return true
            position += n
            if ((0 until n).any { chunk[it] != ZERO }) return false
        }
    }

    private companion object {
        const val CHUNK_BYTES = 8 * 1024
        const val ZERO: Byte = 0
    }
}

/** One step through a journal's records. */
internal sealed interface ScanStep {
    /** A complete, checked record. */
    data class Record(
        val offset: Long,
        val seq: Long,
        val record: JournalRecord,
    ) : ScanStep

    /** The clean end of the file. */
    data object End : ScanStep

    /**
     * The file ends inside a record, or in zeros. That is what a crash
     * mid-append leaves behind, and it is legal: the records before it stand.
     */
    data class TornTail(
        val offset: Long,
        val bytes: Long,
    ) : ScanStep

    /** A complete record that fails its checks. Records after it cannot be trusted. */
    data class Damaged(
        val offset: Long,
        val reason: String,
    ) : ScanStep
}

/**
 * Walks the records after the header. It stops for good at the first
 * torn or damaged record: framing past a bad length is meaningless, and
 * LevelDB stops there too.
 */
internal class RecordScanner(
    private val input: CountingInput,
    private val fileLength: Long,
) {
    private var previousSeq: Long? = null

    fun next(): ScanStep {
        val start = input.position
        val frame = ByteArray(RecordCodec.FRAME_BYTES)
        val got = input.readUpTo(frame)
        val length = if (got == frame.size) RecordCodec.payloadLength(frame) else 0
        return when {
            got == 0 -> ScanStep.End
            got < frame.size -> torn(start)
            length > JournalFormat.MAX_PAYLOAD_BYTES -> damaged(start, frame, null, "length is out of range")
            length > fileLength - input.position -> torn(start)
            else -> readRecord(start, frame, ByteArray(length.toInt()))
        }
    }

    private fun readRecord(
        start: Long,
        frame: ByteArray,
        payload: ByteArray,
    ): ScanStep {
        if (input.readUpTo(payload) < payload.size) return torn(start)
        val seq = RecordCodec.seq(frame)
        val expected = previousSeq?.plus(1)
        val problem =
            when {
                !RecordCodec.crcMatches(frame, payload) -> "checksum does not match"
                expected != null && seq != expected -> "sequence jumps from ${expected - 1} to $seq"
                else -> null
            }
        val record = if (problem == null) RecordCodec.decode(frame, payload) else null
        return if (record == null) {
            damaged(start, frame, payload, problem ?: "record does not parse")
        } else {
            previousSeq = seq
            ScanStep.Record(start, seq, record)
        }
    }

    /**
     * Walks the remaining frames by their headers alone, skipping every
     * payload except a Rebase marker's (49 bytes, CRC-checked): what the
     * launch scan needs to classify a journal without replaying it
     * (FORMAT.md, "Clean journals"). Stops at the end, a torn or
     * zero-filled tail. Checks no other CRC, so it only classifies;
     * replay is what trusts a record.
     */
    fun summarize(): JournalSummary {
        // No records at all is a run that began and was killed before its first edit landed: edits
        // began, so it stays an ordinary journal (a changed file then opens as a conflict, never the
        // outside version silently). Clean is a History record (or several) straight after the header,
        // or a Rebase with nothing after it: any other record, an undo marker included, means edits began.
        var holdsEdits = true
        var rebase: BaseFingerprint? = null
        var rebaseSeq = 0L
        var onlyHistory = true
        while (true) {
            val frame = nextWholeFrame() ?: break
            val rebased =
                if (RecordCodec.mayBeRebase(frame)) {
                    readRebase(frame) // Reads the payload, whatever marker of that size it turns out to be.
                } else {
                    null.also { input.skip(RecordCodec.payloadLength(frame)) }
                }
            if (rebased != null) {
                rebase = rebased
                rebaseSeq = RecordCodec.seq(frame)
                holdsEdits = false
            } else {
                val stillClean = onlyHistory && rebase == null && RecordCodec.isHistory(frame)
                onlyHistory = stillClean
                holdsEdits = !stillClean
            }
        }
        return JournalSummary(holdsEdits, rebase, rebaseSeq)
    }

    /** The next frame header whose payload is all in the file; null at the end, a torn or a zero-filled tail. */
    private fun nextWholeFrame(): ByteArray? {
        val frame = ByteArray(RecordCodec.FRAME_BYTES)
        val whole =
            input.readUpTo(frame) == frame.size &&
                frame.any { it != ZERO } &&
                RecordCodec.payloadLength(frame) <= fileLength - input.position
        return frame.takeIf { whole }
    }

    private fun readRebase(frame: ByteArray): BaseFingerprint? {
        val payload = ByteArray(RecordCodec.REBASE_PAYLOAD_BYTES)
        if (input.readUpTo(payload) < payload.size || !RecordCodec.crcMatches(frame, payload)) return null
        return (RecordCodec.decode(frame, payload) as? JournalRecord.Rebase)?.base
    }

    private fun torn(start: Long) = ScanStep.TornTail(start, fileLength - start)

    // Some file systems extend a file before its data lands, so a crash
    // can leave a zero-filled tail. That is a torn write, not damage.
    private fun damaged(
        start: Long,
        frame: ByteArray,
        payload: ByteArray?,
        reason: String,
    ): ScanStep {
        val allZero = frame.all { it == ZERO } && (payload?.all { it == ZERO } ?: true) && input.restIsZero()
        return if (allZero) torn(start) else ScanStep.Damaged(start, reason)
    }

    private companion object {
        const val ZERO: Byte = 0
    }
}

/**
 * What [RecordScanner.summarize] found, without replaying anything.
 *
 * @property holdsEdits true unless the journal is clean: History records
 *   alone straight after the header, or a Rebase with nothing after it.
 *   Any other record (an edit, a snapshot, an undo marker), and no records
 *   at all (a run killed before its first edit landed), mean edits began
 *   that the file may not have.
 * @property rebase the base the newest Rebase names, or null if there is none.
 * @property rebaseSeq that Rebase's sequence number; 0 when there is none.
 */
internal class JournalSummary(
    val holdsEdits: Boolean,
    val rebase: BaseFingerprint?,
    val rebaseSeq: Long,
)
