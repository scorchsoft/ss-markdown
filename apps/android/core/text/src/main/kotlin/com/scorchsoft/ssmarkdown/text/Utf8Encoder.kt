package com.scorchsoft.ssmarkdown.text

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction

/**
 * Writes a [Snapshot] back to UTF-8 bytes with the file's own line breaks
 * and BOM, streaming chunk by chunk. For a file opened and saved without
 * edits the output is byte-identical to the input.
 *
 * Runs on any thread: it only reads the snapshot.
 */
public object Utf8Encoder {
    private const val BUFFER_SIZE = 64 * 1024

    /** U+FFFD, written in place of a lone surrogate; see [write]. */
    private val REPLACEMENT = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * Encodes [text] to [out]. A lone surrogate cannot exist in UTF-8; one
     * only appears if an edit split a pair (the editor moves by
     * [GraphemeBoundaries] so it does not), and it is written as U+FFFD
     * rather than failing the save.
     */
    public fun write(
        text: Snapshot,
        format: TextFileFormat,
        out: OutputStream,
    ) {
        if (format.hasByteOrderMark) out.write(UTF8_BOM)
        val sink = EncodingSink(out)
        val endings = format.lineEndings
        var line = 0
        text.forEachChunk { chars, start, count ->
            for (i in start until start + count) {
                val c = chars[i]
                if (c == '\n') sink.put(endings.endingAfter(line++).chars) else sink.put(c)
            }
        }
        sink.finish()
    }

    /** Encodes [text] into a new byte array; for small files and tests. */
    public fun encode(
        text: Snapshot,
        format: TextFileFormat,
    ): ByteArray = ByteArrayOutputStream(text.length + text.length / 2).also { write(text, format, it) }.toByteArray()

    /** Stages chars, encodes them in blocks and writes the bytes out. */
    private class EncodingSink(
        private val out: OutputStream,
    ) {
        private val encoder: CharsetEncoder =
            Charsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(REPLACEMENT)
        private val chars = CharBuffer.allocate(BUFFER_SIZE)
        private val bytes = ByteBuffer.allocate(BUFFER_SIZE * 3)

        fun put(c: Char) {
            if (!chars.hasRemaining()) encode(endOfInput = false)
            chars.put(c)
        }

        fun put(s: String) {
            for (c in s) put(c)
        }

        fun finish() {
            encode(endOfInput = true)
            encoder.flush(bytes)
            writeBytes()
        }

        /** A high surrogate at the end of a block stays in [chars] for the next one. */
        private fun encode(endOfInput: Boolean) {
            chars.flip()
            encoder.encode(chars, bytes, endOfInput)
            chars.compact()
            writeBytes()
        }

        private fun writeBytes() {
            out.write(bytes.array(), 0, bytes.position())
            bytes.clear()
        }
    }
}
