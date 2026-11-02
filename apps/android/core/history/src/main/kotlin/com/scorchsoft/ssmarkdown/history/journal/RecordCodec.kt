package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32C

/**
 * Record framing, after LevelDB's log format (doc/log_format.md):
 *
 * ```
 * [length u32][crc u32][type u8][seq u64][payload: length bytes]
 * ```
 *
 * Little-endian like LevelDB. `crc` is a [MaskedCrc] over type, seq and
 * payload. FORMAT.md has the payload layouts.
 */
@Suppress("TooManyFunctions") // One codec: the frame, then a write and a read for each payload type.
internal object RecordCodec {
    const val FRAME_BYTES = 17
    private const val CRC_START = 8
    private const val INT_BYTES = 4
    private const val OP_FIXED_BYTES = 3L * INT_BYTES
    private const val TYPE_OP = 1
    private const val TYPE_SNAPSHOT = 2
    private const val TYPE_MARKER = 3
    private const val TYPE_HISTORY = 4
    private const val FLAG_NEWEST_OPEN = 1
    private const val MAX_FLAGS = 1
    private const val LONG_BYTES = 8

    /** A Rebase marker's payload: the kind byte, size, lastModified and the SHA-256. */
    const val REBASE_PAYLOAD_BYTES = 1 + 2 * LONG_BYTES + BaseFingerprint.SHA256_BYTES

    /** The framed bytes for [record] at [seq], or null if its payload is over the limit. */
    fun encode(
        record: JournalRecord,
        seq: Long,
    ): ByteArray? {
        val type: Int
        val payloadSize: Long
        when (record) {
            is JournalRecord.Op -> {
                type = TYPE_OP
                payloadSize = OP_FIXED_BYTES + Wtf8.encodedLength(record.edit.deleted) +
                    Wtf8.encodedLength(record.edit.inserted)
            }

            is JournalRecord.Snapshot -> {
                type = TYPE_SNAPSHOT
                payloadSize = Wtf8.encodedLength(record.text).toLong()
            }

            is JournalRecord.Marker -> {
                type = TYPE_MARKER
                payloadSize = 1L + Wtf8.encodedLength(record.label)
            }

            is JournalRecord.Step -> {
                type = TYPE_MARKER
                payloadSize = if (record.kind == StepKind.End) 1L else 1L + INT_BYTES
            }

            is JournalRecord.History -> {
                type = TYPE_HISTORY
                payloadSize = historySize(record.history)
            }

            is JournalRecord.Rebase -> {
                type = TYPE_MARKER
                payloadSize = REBASE_PAYLOAD_BYTES.toLong()
            }
        }
        if (payloadSize > JournalFormat.MAX_PAYLOAD_BYTES) return null
        val frame = ByteBuffer.allocate(FRAME_BYTES + payloadSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        frame
            .putInt(payloadSize.toInt())
            .putInt(0)
            .put(type.toByte())
            .putLong(seq)
        writePayload(frame, record)
        val bytes = frame.array()
        ByteBuffer
            .wrap(
                bytes,
            ).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(INT_BYTES, MaskedCrc.of(bytes, CRC_START, bytes.size))
        return bytes
    }

    private fun writePayload(
        frame: ByteBuffer,
        record: JournalRecord,
    ) {
        when (record) {
            is JournalRecord.Op -> {
                putEdit(frame, record.edit)
            }

            is JournalRecord.Snapshot -> {
                frame.put(Wtf8.encode(record.text))
            }

            is JournalRecord.Marker -> {
                frame.put(record.kind.code.toByte()).put(Wtf8.encode(record.label))
            }

            is JournalRecord.Step -> {
                frame.put(record.kind.code.toByte())
                if (record.kind != StepKind.End) frame.putInt(record.opCount)
            }

            is JournalRecord.History -> {
                writeHistory(frame, record.history)
            }

            is JournalRecord.Rebase -> {
                frame
                    .put(REBASE_MARKER_CODE.toByte())
                    .putLong(record.base.size)
                    .putLong(record.base.lastModified)
                    .put(record.base.sha256)
            }
        }
    }

    // History payload: flags u8, then the undo steps and the redo steps, each as
    // stepCount u32 and per step opCount u32 plus that many Op payloads.
    private fun historySize(history: JournalHistory): Long = 1L + stepsSize(history.undo) + stepsSize(history.redo)

    private fun stepsSize(steps: List<List<TextEdit>>): Long =
        INT_BYTES +
            steps.sumOf { step ->
                INT_BYTES +
                    step.sumOf { OP_FIXED_BYTES + Wtf8.encodedLength(it.deleted) + Wtf8.encodedLength(it.inserted) }
            }

    private fun writeHistory(
        frame: ByteBuffer,
        history: JournalHistory,
    ) {
        frame.put((if (history.newestOpen) FLAG_NEWEST_OPEN else 0).toByte())
        for (steps in listOf(history.undo, history.redo)) {
            frame.putInt(steps.size)
            for (step in steps) {
                frame.putInt(step.size)
                step.forEach { edit -> putEdit(frame, edit) }
            }
        }
    }

    private fun putEdit(
        frame: ByteBuffer,
        edit: TextEdit,
    ) {
        frame.putInt(edit.offset)
        putSized(frame, Wtf8.encode(edit.deleted))
        putSized(frame, Wtf8.encode(edit.inserted))
    }

    private fun putSized(
        frame: ByteBuffer,
        bytes: ByteArray,
    ) {
        frame.putInt(bytes.size).put(bytes)
    }

    /** The payload length a frame header declares, as an unsigned value. */
    fun payloadLength(frame: ByteArray): Long =
        ByteBuffer
            .wrap(frame)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getInt(0)
            .toUInt()
            .toLong()

    /** True when a frame header declares a History record. */
    fun isHistory(frame: ByteArray): Boolean = frame[CRC_START].toInt() == TYPE_HISTORY

    /** True when a frame header declares a Marker whose payload is the size of a Rebase. */
    fun mayBeRebase(frame: ByteArray): Boolean =
        frame[CRC_START].toInt() == TYPE_MARKER && payloadLength(frame) == REBASE_PAYLOAD_BYTES.toLong()

    /** The sequence number a frame header declares. */
    fun seq(frame: ByteArray): Long = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getLong(CRC_START + 1)

    /** True when the stored CRC matches [frame] (17 bytes) plus [payload]. */
    fun crcMatches(
        frame: ByteArray,
        payload: ByteArray,
    ): Boolean {
        val crc = CRC32C()
        crc.update(frame, CRC_START, FRAME_BYTES - CRC_START)
        crc.update(payload)
        val stored = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(INT_BYTES)
        return MaskedCrc.mask(crc.value.toInt()) == stored
    }

    /** Decodes a CRC-checked payload; null when it does not parse as its type. */
    fun decode(
        frame: ByteArray,
        payload: ByteArray,
    ): JournalRecord? =
        when (frame[CRC_START].toInt()) {
            TYPE_OP -> decodeOp(payload)
            TYPE_SNAPSHOT -> Wtf8.decode(payload)?.let { JournalRecord.Snapshot(it) }
            TYPE_MARKER -> decodeMarker(payload)
            TYPE_HISTORY -> decodeHistory(payload)
            else -> null
        }

    private fun decodeOp(payload: ByteArray): JournalRecord? {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val edit = readEdit(buffer, payload)
        return if (edit != null && !buffer.hasRemaining()) JournalRecord.Op(edit) else null
    }

    private fun readEdit(
        buffer: ByteBuffer,
        payload: ByteArray,
    ): TextEdit? {
        val offset = if (buffer.remaining() >= INT_BYTES) buffer.getInt() else -1
        val deleted = readSized(buffer, payload)
        val inserted = readSized(buffer, payload)
        return if (offset >= 0 && deleted != null && inserted != null) TextEdit(offset, deleted, inserted) else null
    }

    private fun decodeHistory(payload: ByteArray): JournalRecord? {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val flags = if (buffer.hasRemaining()) buffer.get().toInt() else -1
        val undo = if (flags in 0..MAX_FLAGS) readSteps(buffer, payload) else null
        val redo = if (undo != null) readSteps(buffer, payload) else null
        return if (redo != null && !buffer.hasRemaining()) {
            JournalRecord.History(JournalHistory(undo!!, redo, newestOpen = flags and FLAG_NEWEST_OPEN != 0))
        } else {
            null
        }
    }

    // Every count is checked against the bytes left before anything is
    // allocated: an edit takes at least OP_FIXED_BYTES, so a damaged count
    // can never ask for more steps or edits than the payload could hold.
    @Suppress("ReturnCount") // Each damaged count ends the parse.
    private fun readSteps(
        buffer: ByteBuffer,
        payload: ByteArray,
    ): List<List<TextEdit>>? {
        val count = readCount(buffer, INT_BYTES) ?: return null
        val steps = ArrayList<List<TextEdit>>(count)
        repeat(count) {
            val size = readCount(buffer, OP_FIXED_BYTES.toInt()) ?: return null
            val step = ArrayList<TextEdit>(size)
            repeat(size) { step += readEdit(buffer, payload) ?: return null }
            steps += step
        }
        return steps
    }

    private fun readCount(
        buffer: ByteBuffer,
        minBytesEach: Int,
    ): Int? {
        val count = if (buffer.remaining() >= INT_BYTES) buffer.getInt() else -1
        return count.takeIf { it >= 0 && it.toLong() * minBytesEach <= buffer.remaining() }
    }

    private fun readSized(
        buffer: ByteBuffer,
        payload: ByteArray,
    ): String? {
        val size = if (buffer.remaining() >= INT_BYTES) buffer.getInt() else -1
        if (size !in 0..buffer.remaining()) return null
        val text = Wtf8.decode(payload, buffer.position(), size)
        buffer.position(buffer.position() + size)
        return text
    }

    private fun decodeMarker(payload: ByteArray): JournalRecord? {
        val code = payload.firstOrNull()?.toInt() ?: return null
        if (code == REBASE_MARKER_CODE) return decodeRebase(payload)
        StepKind.fromCode(code)?.let { return decodeStep(it, payload) }
        val kind = MarkerKind.fromCode(code)
        val label = if (kind != null) Wtf8.decode(payload, 1, payload.size - 1) else null
        return if (kind != null && label != null) JournalRecord.Marker(kind, label) else null
    }

    private fun decodeRebase(payload: ByteArray): JournalRecord? {
        if (payload.size != REBASE_PAYLOAD_BYTES) return null
        val buffer = ByteBuffer.wrap(payload, 1, payload.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        val size = buffer.getLong()
        val lastModified = buffer.getLong()
        val sha = ByteArray(BaseFingerprint.SHA256_BYTES).also { buffer.get(it) }
        return if (size >= 0) JournalRecord.Rebase(BaseFingerprint(size, lastModified, sha)) else null
    }

    private fun decodeStep(
        kind: StepKind,
        payload: ByteArray,
    ): JournalRecord? {
        if (kind == StepKind.End) return if (payload.size == 1) JournalRecord.Step(kind) else null
        if (payload.size != 1 + INT_BYTES) return null
        val count = ByteBuffer.wrap(payload, 1, INT_BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt()
        return if (count > 0) JournalRecord.Step(kind, count) else null
    }
}
