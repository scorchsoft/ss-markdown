package com.scorchsoft.ssmarkdown.history.journal

import java.util.zip.CRC32C

/**
 * CRC32C as LevelDB stores it: masked (`crc32c::Mask`, rotate right by
 * 15 bits and add a constant).
 *
 * Masking matters because a journal can contain journal bytes: a
 * snapshot of a document that happens to hold CRCs. Computing a plain
 * CRC over data that embeds CRCs is weaker; the mask breaks that.
 * `java.util.zip.CRC32C` (Java 9+) is hardware-accelerated on ARMv8.
 */
internal object MaskedCrc {
    private const val ROTATE = 15
    private const val DELTA = 0xa282ead8.toInt()

    /** Masked CRC32C of `bytes[from until to]`, the value stored on disk. */
    fun of(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): Int {
        val crc = CRC32C()
        crc.update(bytes, from, to - from)
        return mask(crc.value.toInt())
    }

    /** Masks a raw CRC32C value. */
    fun mask(crc: Int): Int = ((crc ushr ROTATE) or (crc shl (Int.SIZE_BITS - ROTATE))) + DELTA
}
