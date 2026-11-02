package com.scorchsoft.ssmarkdown.history.journal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What reading a header found. The reader and the store map it to their own results. */
internal sealed interface HeaderRead {
    /** A valid header. */
    data class Ok(
        val header: JournalHeader,
    ) : HeaderRead

    /** Our magic, but a version this build does not know. Read-only, never deleted. */
    data class Unsupported(
        val formatVersion: Int,
    ) : HeaderRead

    /** Not a journal, or a damaged header. Nothing in the file can be trusted. */
    data class Unreadable(
        val reason: String,
    ) : HeaderRead
}

/**
 * The file header (FORMAT.md):
 *
 * ```
 * magic "SSMJ" | version u16 | bodyLength u32 | body | crc u32
 * body = createdAt i64 | size i64 | lastModified i64 | sha256[32] | keyLength u32 | key (WTF-8)
 * ```
 *
 * The version sits straight after the magic and is read before anything
 * else, so a future format may change everything after it and an older
 * build still recognises the file as "ours, but newer" rather than as
 * garbage to quarantine.
 */
internal object HeaderCodec {
    private const val MAGIC_BYTES = 4
    private const val VERSION_BYTES = 2
    private const val INT_BYTES = 4
    private const val PREAMBLE_BYTES = MAGIC_BYTES + VERSION_BYTES + INT_BYTES
    private const val FIXED_BODY_BYTES = 3 * Long.SIZE_BYTES + BaseFingerprint.SHA256_BYTES + INT_BYTES

    // Document keys are URIs; 64 KiB is far beyond any real one and stops
    // a damaged length from asking for a huge buffer.
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val U16_MASK = 0xFFFF

    fun encode(header: JournalHeader): ByteArray {
        val key = Wtf8.encode(header.documentKey)
        val bodyLength = FIXED_BODY_BYTES + key.size
        val buffer = ByteBuffer.allocate(PREAMBLE_BYTES + bodyLength + INT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(JournalFormat.MAGIC).putShort(header.formatVersion.toShort()).putInt(bodyLength)
        buffer.putLong(header.createdAtMillis).putLong(header.base.size).putLong(header.base.lastModified)
        buffer.put(header.base.sha256).putInt(key.size).put(key)
        val bytes = buffer.array()
        buffer.putInt(MaskedCrc.of(bytes, MAGIC_BYTES, bytes.size - INT_BYTES))
        return bytes
    }

    fun read(input: CountingInput): HeaderRead {
        val preamble = ByteArray(PREAMBLE_BYTES)
        val got = input.readUpTo(preamble)
        if (got < MAGIC_BYTES || !preamble.copyOf(MAGIC_BYTES).contentEquals(JournalFormat.MAGIC)) {
            return HeaderRead.Unreadable("not a journal")
        }
        val buffer = ByteBuffer.wrap(preamble).order(ByteOrder.LITTLE_ENDIAN)
        val version = if (got >= MAGIC_BYTES + VERSION_BYTES) buffer.getShort(MAGIC_BYTES).toInt() and U16_MASK else 0
        return when {
            version > JournalFormat.VERSION -> HeaderRead.Unsupported(version)
            got < PREAMBLE_BYTES || version < 1 -> HeaderRead.Unreadable("header is damaged")
            else -> readBody(input, preamble, buffer.getInt(MAGIC_BYTES + VERSION_BYTES), version)
        }
    }

    private fun readBody(
        input: CountingInput,
        preamble: ByteArray,
        bodyLength: Int,
        version: Int,
    ): HeaderRead {
        if (bodyLength !in FIXED_BODY_BYTES..MAX_BODY_BYTES) return HeaderRead.Unreadable("header length is damaged")
        val rest = ByteArray(bodyLength + INT_BYTES)
        val complete = input.readUpTo(rest) == rest.size
        val all = preamble + rest
        val stored = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN).getInt(all.size - INT_BYTES)
        return when {
            !complete -> {
                HeaderRead.Unreadable("header is truncated")
            }

            stored != MaskedCrc.of(all, MAGIC_BYTES, all.size - INT_BYTES) -> {
                HeaderRead.Unreadable("header checksum does not match")
            }

            else -> {
                parseBody(rest, bodyLength, version)?.let { HeaderRead.Ok(it) }
                    ?: HeaderRead.Unreadable("header fields are damaged")
            }
        }
    }

    private fun parseBody(
        rest: ByteArray,
        bodyLength: Int,
        version: Int,
    ): JournalHeader? {
        val body = ByteBuffer.wrap(rest, 0, bodyLength).order(ByteOrder.LITTLE_ENDIAN)
        val createdAt = body.getLong()
        val size = body.getLong()
        val lastModified = body.getLong()
        val sha = ByteArray(BaseFingerprint.SHA256_BYTES).also { body.get(it) }
        val keyLength = body.getInt()
        val key = if (keyLength == body.remaining()) Wtf8.decode(rest, body.position(), keyLength) else null
        return if (key != null && size >= 0) {
            JournalHeader(key, BaseFingerprint(size, lastModified, sha), createdAt, version)
        } else {
            null
        }
    }
}
