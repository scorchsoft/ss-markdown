package com.scorchsoft.ssmarkdown.history.versions

import com.scorchsoft.ssmarkdown.history.journal.AtomicFile
import com.scorchsoft.ssmarkdown.history.journal.MaskedCrc
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * One stored version body: a keyframe (the whole text) or a backward
 * delta, possibly deflated.
 *
 * On disk: `length u32` (payload bytes), `crc u32` (masked CRC32C of
 * flags and payload), `flags u8`, payload. See VERSIONS-FORMAT.md.
 *
 * @property keyframe true for a whole text, false for a delta.
 * @property body the uncompressed text bytes (WTF-8) or delta bytes.
 */
internal class PackRecord(
    val keyframe: Boolean,
    val body: ByteArray,
) {
    /** Encodes the record, deflating the body when that makes it smaller. */
    fun encode(): ByteArray {
        val deflated = deflate(body)
        val compressed = deflated.size < body.size
        val payload = if (compressed) deflated else body
        val flags = (if (keyframe) FLAG_KEYFRAME else 0) or (if (compressed) FLAG_DEFLATED else 0)
        val out = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out
            .putInt(payload.size)
            .putInt(0)
            .put(flags.toByte())
            .put(payload)
        val bytes = out.array()
        val crc = MaskedCrc.of(bytes, CRC_FROM, bytes.size)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(LENGTH_BYTES, crc)
        return bytes
    }

    companion object {
        const val HEADER_BYTES: Int = 9
        private const val LENGTH_BYTES = 4
        private const val CRC_FROM = 8
        private const val FLAG_KEYFRAME = 1
        private const val FLAG_DEFLATED = 2
        private const val KNOWN_FLAGS = FLAG_KEYFRAME or FLAG_DEFLATED
        private const val BUFFER = 64 * 1024

        /**
         * Decodes a whole encoded record, or null when its CRC, flags or
         * compressed stream are wrong. [maxBody] bounds inflation, so a
         * damaged record cannot ask for unbounded memory.
         */
        @Suppress("ReturnCount") // Each check rejects one way a damaged record can look.
        fun decode(
            bytes: ByteArray,
            maxBody: Int,
        ): PackRecord? {
            if (bytes.size < HEADER_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val length = buffer.int
            val crc = buffer.int
            val flags = buffer.get().toInt()
            val valid =
                length == bytes.size - HEADER_BYTES && crc == MaskedCrc.of(bytes, CRC_FROM, bytes.size) &&
                    flags and KNOWN_FLAGS.inv() == 0
            if (!valid) return null
            val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size)
            val body = if (flags and FLAG_DEFLATED != 0) inflate(payload, maxBody) else payload
            return body?.let { PackRecord(flags and FLAG_KEYFRAME != 0, it) }
        }

        private fun deflate(bytes: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
            try {
                deflater.setInput(bytes)
                deflater.finish()
                val out = ByteArrayOutputStream(bytes.size / 2 + BUFFER_MIN)
                val chunk = ByteArray(BUFFER)
                while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        private const val BUFFER_MIN = 64

        @Suppress("ReturnCount") // A stalled, oversized or malformed stream is refused where it is found.
        private fun inflate(
            bytes: ByteArray,
            maxBody: Int,
        ): ByteArray? {
            val inflater = Inflater()
            try {
                inflater.setInput(bytes)
                val out = ByteArrayOutputStream(bytes.size * 2)
                val chunk = ByteArray(BUFFER)
                while (!inflater.finished()) {
                    val n = inflater.inflate(chunk)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                    out.write(chunk, 0, n)
                    if (out.size() > maxBody) return null
                }
                return out.toByteArray()
            } catch (ignored: DataFormatException) {
                return null
            } finally {
                inflater.end()
            }
        }
    }
}

/**
 * The append-only file that holds a document's version bodies.
 *
 * Only bytes below the index's recorded length are live: anything past
 * it is an append whose index rename never happened (a crash between
 * the two writes), and it is cut off before the next append.
 *
 * @property file the pack on disk.
 */
internal class PackFile(
    val file: File,
) {
    /**
     * Appends [records] after [validLength], cutting off anything beyond
     * it first, and syncs. Returns each record's offset.
     */
    fun append(
        validLength: Long,
        records: List<ByteArray>,
    ): LongArray {
        file.parentFile?.mkdirs()
        val offsets = LongArray(records.size)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(validLength)
            raf.seek(validLength)
            var position = validLength
            records.forEachIndexed { i, record ->
                offsets[i] = position
                raf.write(record)
                position += record.size
            }
            raf.fd.sync()
        }
        AtomicFile.syncDirectory(file.absoluteFile.parentFile)
        return offsets
    }

    /** The encoded record of [length] bytes at [offset], or null when the file is too short. */
    fun readRaw(
        offset: Long,
        length: Int,
    ): ByteArray? =
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (offset < 0 || offset + length > raf.length()) return null
                ByteArray(length).also {
                    raf.seek(offset)
                    raf.readFully(it)
                }
            }
        } catch (ignored: IOException) {
            null
        }
}
