package com.scorchsoft.ssmarkdown.text

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Streams UTF-8 bytes into a [PieceTree], 64 KB at a time, never holding
 * the file as one String. A UTF-8 BOM is stripped and remembered. Invalid
 * input is *reported*, never replaced with U+FFFD: a replaced byte would
 * be written back on the next save and destroy data the user never saw.
 */
public object Utf8Decoder {
    private const val READ_SIZE = 64 * 1024
    private const val BOM_PROBE = 4
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val FOREIGN_BOMS =
        listOf(
            // UTF-32 first: its little-endian BOM begins with UTF-16LE's.
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) to "UTF-32LE",
            byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte()) to "UTF-32BE",
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to "UTF-16LE",
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to "UTF-16BE",
        )

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
        val endOfInput = fill(input, bytes, BOM_PROBE)
        bytes.flip()
        val foreign = FOREIGN_BOMS.firstOrNull { (bom, _) -> startsWith(bytes, bom) }
        return if (foreign !=
            null
        ) {
            DecodeResult.UnsupportedEncoding(foreign.second)
        } else {
            decodeUtf8(input, bytes, endOfInput)
        }
    }

    /** Decodes from [bytes] (flipped, holding the first bytes of the file) to the end of [input]. */
    private fun decodeUtf8(
        input: InputStream,
        bytes: ByteBuffer,
        atEnd: Boolean,
    ): DecodeResult {
        val hasBom = startsWith(bytes, UTF8_BOM)
        if (hasBom) bytes.position(UTF8_BOM.size)
        val decoder = newDecoder()
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
        if (result.isError) return DecodeResult.Malformed(discarded + bytes.position())
        drain(chars, builder)
        val built = builder.build()
        return DecodeResult.Decoded(built.text, TextFileFormat(built.lineEndings, hasBom))
    }

    private fun newDecoder(): CharsetDecoder =
        Charsets.UTF_8
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
}
