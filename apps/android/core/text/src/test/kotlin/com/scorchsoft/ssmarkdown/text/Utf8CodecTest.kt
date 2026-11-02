package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.Random

class Utf8CodecTest {
    @Test
    fun `a UTF-8 BOM is stripped, remembered and written back`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "# Hi\n".toByteArray()
        val decoded = decode(bytes)
        assertEquals("# Hi\n", decoded.text.toString())
        assertTrue(decoded.format.hasByteOrderMark)
        assertRoundTrip(bytes)
    }

    @Test
    fun `invalid UTF-8 is reported with its byte offset, never replaced`() {
        val bytes = "ok\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        assertEquals(DecodeResult.Malformed(3), Utf8Decoder.decode(bytes))
    }

    @Test
    fun `a truncated sequence at the end of the file is malformed`() {
        val bytes = "end".toByteArray() + byteArrayOf(0xE2.toByte(), 0x82.toByte())
        assertEquals(DecodeResult.Malformed(3), Utf8Decoder.decode(bytes))
    }

    @Test
    fun `the offset of a bad byte deep in a large file counts every buffer before it`() {
        val prefix = ByteArray(200_000) { 'a'.code.toByte() }
        val bytes = prefix + byteArrayOf(0xFF.toByte()) + "tail".toByteArray()
        assertEquals(DecodeResult.Malformed(200_000), Utf8Decoder.decode(bytes))
    }

    @Test
    fun `UTF-32 BOMs are refused by name`() {
        assertEquals(
            DecodeResult.UnsupportedEncoding("UTF-32LE"),
            Utf8Decoder.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)),
        )
        assertEquals(
            DecodeResult.UnsupportedEncoding("UTF-32BE"),
            Utf8Decoder.decode(byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte())),
        )
    }

    @Test
    fun `a failing stream is a value, not an exception`() {
        val broken =
            object : InputStream() {
                override fun read(): Int = throw IOException("provider went away")
            }
        val result = Utf8Decoder.decode(broken)
        assertTrue(result is DecodeResult.ReadFailed)
        assertEquals("provider went away", (result as DecodeResult.ReadFailed).cause.message)
    }

    @Test
    fun `line endings are detected`() {
        assertEquals(LineEndings.Uniform(LineEnding.LF), decode("a\nb\n").format.lineEndings)
        assertEquals(LineEndings.Uniform(LineEnding.CRLF), decode("a\r\nb\r\n").format.lineEndings)
        assertEquals(LineEndings.Uniform(LineEnding.CR), decode("a\rb\r").format.lineEndings)
        assertEquals(LineEndings.Uniform(LineEnding.LF), decode("no breaks").format.lineEndings)
        val mixed = decode("a\r\nb\r\nc\nd\r\n").format.lineEndings
        assertTrue(mixed is LineEndings.Mixed)
        mixed as LineEndings.Mixed
        assertEquals(LineEnding.CRLF, mixed.dominant)
        assertEquals(1, mixed.exceptionCount)
        assertEquals(LineEnding.LF, mixed.endingAfter(2))
        assertEquals(LineEnding.CRLF, mixed.endingAfter(3))
    }

    @Test
    fun `unedited files round trip byte for byte`() {
        val cases =
            listOf(
                "",
                "no final newline",
                "lf\nonly\n",
                "crlf\r\nonly\r\n",
                "cr\ronly\r",
                "mixed\r\nlf\ncr\rcrlf\r\n\r\n\n",
                "\r\n\r\n",
                "trailing cr\r",
                "emoji 👩‍💻 and 🇬🇧\r\n",
                "日本語\r\nе́\n",
            )
        for (text in cases) {
            assertRoundTrip(text.toByteArray())
            assertRoundTrip(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray())
        }
    }

    @Test
    fun `random mixed documents round trip, including across chunk and read boundaries`() {
        val random = Random(2_026)
        repeat(30) {
            val text = TextGenerator.text(random, random.nextInt(150_000))
            assertRoundTrip(text.toByteArray())
        }
    }

    @Test
    fun `a CRLF split across decoder reads is still one break`() {
        // 64 KB read boundary falls between \r and \n.
        val text = "a".repeat(64 * 1024 - 1) + "\r\nend"
        val decoded = decode(text)
        assertEquals(2, decoded.text.lineCount)
        assertEquals(LineEndings.Uniform(LineEnding.CRLF), decoded.format.lineEndings)
        assertRoundTrip(text.toByteArray())
    }

    @Test
    fun `chunks never end on a high surrogate`() {
        val text = "a" + "😀".repeat(100_000)
        val tree = decode(text).text
        assertEquals(text, tree.toString())
        tree.snapshot().pieces.forEach { assertFalse(it.charAt(it.length - 1).isHighSurrogate()) }
    }

    @Test
    fun `edits to a CRLF file are saved as CRLF`() {
        val document = TextDocument.from(decode("one\r\ntwo\r\n"))
        document.replace(3, 0, "\nnew")
        assertEquals("one\r\nnew\r\ntwo\r\n", saved(document))
    }

    @Test
    fun `a mixed file keeps its odd lines where they moved`() {
        val document = TextDocument.from(decode("a\r\nb\nc\r\nd\r\n"))
        document.replace(0, 0, "top\n")
        assertEquals("top\r\na\r\nb\nc\r\nd\r\n", saved(document))
        document.replace(document.text.lineStartOffset(2), 2, "")
        assertEquals("top\r\na\r\nc\r\nd\r\n", saved(document))
        // Undo restores the text; a break that was deleted and restored comes
        // back in the dominant style (documented on TextDocument).
        document.undo()
        document.undo()
        assertEquals("a\r\nb\r\nc\r\nd\r\n", saved(document))
    }

    @Test
    fun `converting line endings rewrites every break`() {
        val document = TextDocument.from(decode("a\r\nb\nc\r"))
        document.convertLineEndings(LineEnding.LF)
        assertEquals("a\nb\nc\n", saved(document))
    }

    @Test
    fun `a lone surrogate left by a careless edit is saved as U+FFFD`() {
        val tree = PieceTree.of("x😀y")
        tree.delete(2, 1)
        val bytes = Utf8Encoder.encode(tree.snapshot(), TextFileFormat.DEFAULT)
        assertEquals("x�y", String(bytes))
    }

    private fun decode(text: String): DecodeResult.Decoded = decode(text.toByteArray())

    private fun decode(bytes: ByteArray): DecodeResult.Decoded = Utf8Decoder.decode(bytes) as DecodeResult.Decoded

    private fun saved(document: TextDocument): String =
        Utf8Encoder.encode(document.snapshot(), document.format).decodeToString()

    private fun assertRoundTrip(bytes: ByteArray) {
        val decoded = decode(bytes)
        assertArrayEquals(bytes, Utf8Encoder.encode(decoded.text.snapshot(), decoded.format))
    }
}
