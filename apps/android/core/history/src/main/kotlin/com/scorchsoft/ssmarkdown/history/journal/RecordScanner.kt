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
