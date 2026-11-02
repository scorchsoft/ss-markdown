package com.scorchsoft.ssmarkdown.text

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Streams a text file's bytes into a [PieceTree], 64 KB at a time, never
 * holding the file as one String. UTF-8 is the default; UTF-16 is read
 * too ([TextEncoding]), chosen by its BOM or, without one, by a strong
 * NUL-byte pattern ([sniffUtf16]). The BOM is stripped and remembered, and
 * the encoding goes into the [TextFileFormat], so a save writes the same
 * bytes back. UTF-32 is refused by name ([DecodeResult.UnsupportedEncoding]).
 *
 * Invalid input is *reported*, never replaced with U+FFFD: a replaced byte
 * would be written back on the next save and destroy data the user never saw.
 */
public object Utf8Decoder {
    private const val READ_SIZE = 64 * 1024

    /** Bytes looked at before choosing an encoding: every BOM, and enough text for the NUL pattern. */
    private const val SNIFF_BYTES = 4 * 1024

    /** Fewer UTF-16 units than this is too little to call a NUL pattern. */
    private const val MIN_SNIFF_UNITS = 8

    /** At least this share of units must have the NUL half, and of units look like text. */
    private const val SNIFF_SHARE = 0.9

    private val UTF32_BOMS =
        listOf(
            // Checked before UTF-16: the little-endian UTF-32 BOM begins with UTF-16LE's.
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) to "UTF-32LE",
            byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte()) to "UTF-32BE",
        )
    private val BOM_ORDER = listOf(TextEncoding.UTF_8, TextEncoding.UTF_16LE, TextEncoding.UTF_16BE)

    /** Decodes a whole byte array. */
    public fun decode(bytes: ByteArray): DecodeResult = decode(ByteArrayInputStream(bytes))

    /** Decodes [input] to its end. The caller owns and closes the stream. */
    public fun decode(input: InputStream): DecodeResult =
        try {
            decodeStream(input)
        } catch (e: IOException) {
            DecodeResult.ReadFailed(e)
        }

    private fun decodeStream(input: InputStream): DecodeResult {
        val bytes = ByteBuffer.allocate(READ_SIZE)
        val endOfInput = fill(input, bytes, SNIFF_BYTES)
        bytes.flip()
        UTF32_BOMS.firstOrNull { (bom, _) -> startsWith(bytes, bom) }?.let {
            return DecodeResult.UnsupportedEncoding(it.second)
        }
        val byBom = BOM_ORDER.firstOrNull { startsWith(bytes, it.byteOrderMark) }
        val encoding = byBom ?: sniffUtf16(bytes) ?: TextEncoding.UTF_8
        return decodeAs(encoding, hasBom = byBom != null, input, bytes, endOfInput)
    }

    /**
     * UTF-16 without a BOM, told apart by its NUL bytes: in mostly-Latin
     * text one half of nearly every 16-bit unit is zero, always the same
     * half, and the other half never is. Also requires the units to look
     * like text (printable or a tab or line break), so 16-bit binary data
     * stays UTF-8 and opens read-only as "not text". A false positive is
     * still safe: valid UTF-16 decodes and encodes back to the same bytes.
     */
    private fun sniffUtf16(bytes: ByteBuffer): TextEncoding? {
        val units = minOf(bytes.remaining(), SNIFF_BYTES) / 2
        if (units < MIN_SNIFF_UNITS) return null
        var zeroLow = 0
        var zeroHigh = 0
        var textLe = 0
        var textBe = 0
        val base = bytes.position()
        for (unit in 0 until units) {
            val first = bytes.get(base + 2 * unit).toInt() and BYTE_MASK
            val second = bytes.get(base + 2 * unit + 1).toInt() and BYTE_MASK
            if (first == 0) zeroLow++
            if (second == 0) zeroHigh++
            if (looksLikeText(first or (second shl BITS_PER_BYTE))) textLe++
            if (looksLikeText((first shl BITS_PER_BYTE) or second)) textBe++
        }
        val most = units * SNIFF_SHARE
        return when {
            zeroHigh >= most && zeroLow == 0 && textLe >= most -> TextEncoding.UTF_16LE
            zeroLow >= most && zeroHigh == 0 && textBe >= most -> TextEncoding.UTF_16BE
            else -> null
        }
    }

    private fun looksLikeText(unit: Int): Boolean =
        unit >= ' '.code || unit == '\t'.code || unit == '\n'.code || unit == '\r'.code

    /** Decodes from [bytes] (flipped, holding the first bytes of the file) to the end of [input]. */
    private fun decodeAs(
        encoding: TextEncoding,
        hasBom: Boolean,
        input: InputStream,
        bytes: ByteBuffer,
        atEnd: Boolean,
    ): DecodeResult {
        if (hasBom) bytes.position(bytes.position() + encoding.bomSize)
        val decoder = newDecoder(Charset.forName(encoding.charsetName))
        val chars = CharBuffer.allocate(READ_SIZE)
        val builder = TextBufferBuilder()
        var endOfInput = atEnd
        var discarded = 0L // bytes compacted away before bytes[0], so errors report file offsets
        var result = decoder.decode(bytes, chars, endOfInput)
        while (!result.isError && !(result.isUnderflow && endOfInput)) {
            drain(chars, builder)
            if (result.isUnderflow) {
                discarded += bytes.position()
                bytes.compact()
                endOfInput = fill(input, bytes, bytes.capacity())
                bytes.flip()
            }
            result = decoder.decode(bytes, chars, endOfInput)
        }
        if (!result.isError) result = decoder.flush(chars)
        if (result.isError) return DecodeResult.Malformed(discarded + bytes.position(), encoding)
        drain(chars, builder)
        val built = builder.build()
        return DecodeResult.Decoded(built.text, TextFileFormat(built.lineEndings, hasBom, encoding))
    }

    private fun newDecoder(charset: Charset): CharsetDecoder =
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

    /** Reads until [bytes] holds at least [atLeast] bytes or the stream ends; true at the end. */
    private fun fill(
        input: InputStream,
        bytes: ByteBuffer,
        atLeast: Int,
    ): Boolean {
        val target = minOf(atLeast, bytes.capacity())
        while (bytes.position() < target) {
            val read = input.read(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining())
            if (read < 0) return true
            bytes.position(bytes.position() + read)
        }
        return false
    }

    private fun drain(
        chars: CharBuffer,
        builder: TextBufferBuilder,
    ) {
        chars.flip()
        builder.append(chars.array(), chars.arrayOffset() + chars.position(), chars.remaining())
        chars.clear()
    }

    private fun startsWith(
        bytes: ByteBuffer,
        prefix: ByteArray,
    ): Boolean =
        bytes.remaining() >= prefix.size && prefix.indices.all { bytes.get(bytes.position() + it) == prefix[it] }

    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
}
