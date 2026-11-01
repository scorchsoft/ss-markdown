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
internal object RecordCodec {
    const val FRAME_BYTES = 17
    private const val CRC_START = 8
    private const val INT_BYTES = 4
    private const val OP_FIXED_BYTES = 3L * INT_BYTES
    private const val TYPE_OP = 1
    private const val TYPE_SNAPSHOT = 2
    private const val TYPE_MARKER = 3

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
                frame.putInt(record.edit.offset)
                putSized(frame, Wtf8.encode(record.edit.deleted))
                putSized(frame, Wtf8.encode(record.edit.inserted))
            }

            is JournalRecord.Snapshot -> {
                frame.put(Wtf8.encode(record.text))
            }

            is JournalRecord.Marker -> {
                frame.put(record.kind.code.toByte()).put(Wtf8.encode(record.label))
            }
        }
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
            else -> null
        }

    private fun decodeOp(payload: ByteArray): JournalRecord? {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val offset = if (buffer.remaining() >= INT_BYTES) buffer.getInt() else -1
        val deleted = readSized(buffer, payload)
        val inserted = readSized(buffer, payload)
        val valid = offset >= 0 && deleted != null && inserted != null && !buffer.hasRemaining()
        return if (valid) JournalRecord.Op(TextEdit(offset, deleted, inserted)) else null
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
        val kind = payload.firstOrNull()?.let { MarkerKind.fromCode(it.toInt()) }
        val label = if (kind != null) Wtf8.decode(payload, 1, payload.size - 1) else null
        return if (kind != null && label != null) JournalRecord.Marker(kind, label) else null
    }
}
