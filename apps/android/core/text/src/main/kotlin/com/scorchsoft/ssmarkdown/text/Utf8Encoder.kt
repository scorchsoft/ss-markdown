package com.scorchsoft.ssmarkdown.text

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction

/**
 * Writes a [Snapshot] back to bytes in the file's own encoding (UTF-8, or
 * UTF-16 when it was opened as UTF-16), line breaks and BOM, streaming
 * chunk by chunk. For a file opened and saved without edits the output is
 * byte-identical to the input.
 *
 * Runs on any thread: it only reads the snapshot.
 */
public object Utf8Encoder {
    private const val BUFFER_SIZE = 64 * 1024

    /** The most bytes one char takes in any [TextEncoding] (UTF-8, 3). */
    private const val MAX_BYTES_PER_CHAR = 3

    /**
     * Encodes [text] to [out]. A lone surrogate cannot be encoded; one only
     * appears if an edit split a pair (the editor moves by
     * [GraphemeBoundaries] so it does not), and it is written as U+FFFD
     * rather than failing the save.
     */
    public fun write(
        text: Snapshot,
        format: TextFileFormat,
        out: OutputStream,
    ) {
        if (format.hasByteOrderMark) out.write(format.encoding.byteOrderMark)
        val sink = EncodingSink(out, Charset.forName(format.encoding.charsetName))
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
        charset: Charset,
    ) {
        private val encoder: CharsetEncoder =
            charset
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith("\uFFFD".toByteArray(charset))
        private val chars = CharBuffer.allocate(BUFFER_SIZE)
        private val bytes = ByteBuffer.allocate(BUFFER_SIZE * MAX_BYTES_PER_CHAR)

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
