package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * UTF-16 files (M2): detected by their BOM, or without one by a strong
 * pattern of NUL bytes, decoded, and written back in the same encoding
 * byte for byte.
 */
class Utf16CodecTest {
    private val le = Charsets.UTF_16LE
    private val be = Charsets.UTF_16BE
    private val bomLe = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val bomBe = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun decode(bytes: ByteArray): DecodeResult.Decoded = Utf8Decoder.decode(bytes) as DecodeResult.Decoded

    private fun assertRoundTrip(bytes: ByteArray) {
        val decoded = decode(bytes)
        assertArrayEquals(bytes, Utf8Encoder.encode(decoded.text.snapshot(), decoded.format))
    }

    @Test
    fun `a UTF-16 BOM selects the encoding, is stripped and is written back`() {
        for ((bom, charset, encoding) in listOf(
            Triple(bomLe, le, TextEncoding.UTF_16LE),
            Triple(bomBe, be, TextEncoding.UTF_16BE),
        )) {
            val bytes = bom + "# Hi\r\nשלום 😀\r\n".toByteArray(charset)
            val decoded = decode(bytes)
            assertEquals("# Hi\nשלום 😀\n", decoded.text.toString())
            assertEquals(encoding, decoded.format.encoding)
            assertTrue(decoded.format.hasByteOrderMark)
            assertEquals(LineEndings.Uniform(LineEnding.CRLF), decoded.format.lineEndings)
            assertRoundTrip(bytes)
        }
    }

    @Test
    fun `UTF-16 without a BOM is recognised by its NUL pattern`() {
        for ((charset, encoding) in listOf(le to TextEncoding.UTF_16LE, be to TextEncoding.UTF_16BE)) {
            val bytes = "# Notes\n\nSome plain text, with a tab\tand é.\n".toByteArray(charset)
            val decoded = decode(bytes)
            assertEquals(encoding, decoded.format.encoding)
            assertFalse(decoded.format.hasByteOrderMark)
            assertTrue(decoded.text.toString().startsWith("# Notes"))
            assertRoundTrip(bytes)
        }
    }

    @Test
    fun `the NUL heuristic leaves UTF-8 and binary-looking bytes alone`() {
        // One NUL in UTF-8 text: still UTF-8 (opened read-only as "not text" upstream).
        assertEquals(TextEncoding.UTF_8, decode("text\u0000more\n".toByteArray()).format.encoding)
        // Little-endian 16-bit numbers: NUL every other byte, but not text.
        val numbers = ByteArray(64) { if (it % 2 == 0) (it % 7 + 1).toByte() else 0 }
        assertEquals(TextEncoding.UTF_8, decode(numbers).format.encoding)
        // Too short to judge.
        assertEquals(TextEncoding.UTF_8, decode(byteArrayOf(0x41, 0)).format.encoding)
    }

    @Test
    fun `UTF-16 files round trip across read boundaries, every line ending kept`() {
        val text = "a".repeat(40_000) + "\r\nmixed\nlines\rend😀" + "b".repeat(40_000) + "\r\n"
        assertRoundTrip(bomLe + text.toByteArray(le))
        assertRoundTrip(text.toByteArray(be))
    }

    @Test
    fun `an edited UTF-16 file is saved in UTF-16 with its BOM and line endings`() {
        val document = TextDocument.from(decode(bomLe + "one\r\ntwo\r\n".toByteArray(le)))
        document.replace(3, 0, "\nnew ✓")
        val saved = Utf8Encoder.encode(document.snapshot(), document.format)
        assertArrayEquals(bomLe + "one\r\nnew ✓\r\ntwo\r\n".toByteArray(le), saved)
    }

    @Test
    fun `a lone surrogate in UTF-16 is malformed, with its byte offset and encoding`() {
        val bytes = bomBe + "ok".toByteArray(be) + byteArrayOf(0xD8.toByte(), 0x00, 0x00, 0x41)
        assertEquals(DecodeResult.Malformed(6, TextEncoding.UTF_16BE), Utf8Decoder.decode(bytes))
    }

    @Test
    fun `every encoding names a charset the platform has`() {
        for (encoding in TextEncoding.entries) assertTrue(Charset.isSupported(encoding.charsetName))
    }
}
