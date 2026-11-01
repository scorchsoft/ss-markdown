package com.scorchsoft.ssmarkdown.history.versions

import com.scorchsoft.ssmarkdown.history.journal.MaskedCrc
import com.scorchsoft.ssmarkdown.history.journal.Wtf8
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What reading an index file came to. */
internal sealed interface IndexRead {
    data class Ok(
        val index: VersionIndex,
    ) : IndexRead

    /** Written by a newer app; left exactly as it is. */
    data class Unsupported(
        val formatVersion: Int,
    ) : IndexRead

    /** Not an index, or damaged. */
    data object Corrupt : IndexRead
}

/**
 * The byte layout of `index.ssv`. See VERSIONS-FORMAT.md, "Index".
 *
 * `magic "SSMV"`, `formatVersion u16`, `bodyLength u32`, body,
 * `crc u32` (masked CRC32C of everything from formatVersion to the end
 * of the body). All little-endian.
 */
internal object IndexCodec {
    const val FORMAT_VERSION: Int = 1
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte())
    private const val PREFIX_BYTES = 10
    private const val CRC_BYTES = 4
    private const val FLAG_PINNED = 1
    private const val FLAG_KEYFRAME = 2
    private const val FLAG_PARENT = 4
    private const val FLAG_LABEL = 8
    private const val SHA_BYTES = 32
    private const val HEX_RADIX = 16

    fun encode(index: VersionIndex): ByteArray {
        val bodyBytes = ByteArrayOutputStream()
        val body = DataOutputStream(bodyBytes)
        body.writeString(index.uri)
        body.writeLongLe(index.nextSeq)
        body.writeLongLe(index.lastOpenedMillis)
        body.writeString(index.packName)
        body.writeLongLe(index.packLength)
        body.writeIntLe(index.branches.size)
        index.branches.toSortedMap().forEach { (id, label) ->
            body.writeIntLe(id)
            body.writeString(label)
        }
        body.writeIntLe(index.versions.size)
        index.versions.forEach { body.writeVersion(it) }
        val bodyArray = bodyBytes.toByteArray()
        val out = ByteBuffer.allocate(PREFIX_BYTES + bodyArray.size + CRC_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        out
            .put(MAGIC)
            .putShort(FORMAT_VERSION.toShort())
            .putInt(bodyArray.size)
            .put(bodyArray)
        val bytes = out.array()
        out.putInt(MaskedCrc.of(bytes, MAGIC.size, PREFIX_BYTES + bodyArray.size))
        return bytes
    }

    fun decode(bytes: ByteArray): IndexRead {
        if (bytes.size < PREFIX_BYTES + CRC_BYTES || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            return IndexRead.Corrupt
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(MAGIC.size)
        val version = buffer.short.toInt()
        val bodyLength = buffer.int
        return when {
            version > FORMAT_VERSION -> IndexRead.Unsupported(version)

            version != FORMAT_VERSION || bodyLength != bytes.size - PREFIX_BYTES - CRC_BYTES -> IndexRead.Corrupt

            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(PREFIX_BYTES + bodyLength) !=
                MaskedCrc.of(bytes, MAGIC.size, PREFIX_BYTES + bodyLength) -> IndexRead.Corrupt

            else -> decodeBody(buffer)
        }
    }

    private fun decodeBody(buffer: ByteBuffer): IndexRead =
        try {
            val uri = buffer.readString()
            val nextSeq = buffer.long
            val lastOpened = buffer.long
            val packName = buffer.readString()
            val packLength = buffer.long
            val branches = LinkedHashMap<Int, String>()
            repeat(buffer.int) { branches[buffer.int] = buffer.readString() }
            val versions = List(buffer.int) { buffer.readVersion() }
            IndexRead.Ok(VersionIndex(uri, nextSeq, lastOpened, packName, packLength, branches, versions))
        } catch (ignored: BufferUnderflowException) {
            IndexRead.Corrupt
        } catch (ignored: IllegalArgumentException) {
            IndexRead.Corrupt
        }

    private fun DataOutputStream.writeVersion(stored: StoredVersion) {
        val info = stored.info
        writeLongLe(info.id.seq)
        writeIntLe(info.branch)
        writeLongLe(info.wallTimeMillis)
        writeByte(info.kind.code)
        val flags =
            (if (info.pinned) FLAG_PINNED else 0) or (if (info.keyframe) FLAG_KEYFRAME else 0) or
                (if (info.parent != null) FLAG_PARENT else 0) or (if (info.label != null) FLAG_LABEL else 0)
        writeByte(flags)
        info.parent?.let { writeLongLe(it.seq) }
        info.label?.let { writeString(it) }
        writeIntLe(info.changes.added)
        writeIntLe(info.changes.removed)
        writeIntLe(info.textLength)
        write(hexToBytes(info.sha256Hex))
        writeLongLe(stored.offset)
        writeIntLe(info.storedBytes)
    }

    private fun ByteBuffer.readVersion(): StoredVersion {
        val seq = long
        val branch = int
        val wall = long
        val kind = requireNotNull(VersionKind.of(get().toInt())) { "unknown version kind" }
        val flags = get().toInt()
        val parent = if (flags and FLAG_PARENT != 0) VersionId(long) else null
        val label = if (flags and FLAG_LABEL != 0) readString() else null
        val changes = LineChangeSummary(int, int)
        val textLength = int
        val sha = ByteArray(SHA_BYTES).also { get(it) }
        val offset = long
        val stored = int
        val info =
            VersionInfo(
                VersionId(seq),
                branch,
                wall,
                kind,
                label,
                flags and FLAG_PINNED != 0,
                parent,
                changes,
                textLength,
                bytesToHex(sha),
                stored,
                flags and FLAG_KEYFRAME != 0,
            )
        return StoredVersion(info, offset)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = Wtf8.encode(value)
        writeIntLe(bytes.size)
        write(bytes)
    }

    private fun ByteBuffer.readString(): String {
        val length = int
        require(length in 0..remaining()) { "string length out of range" }
        val text = requireNotNull(Wtf8.decode(array(), position(), length)) { "malformed string" }
        position(position() + length)
        return text
    }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte()
        }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
