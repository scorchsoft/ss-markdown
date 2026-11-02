package com.scorchsoft.ssmarkdown.platform

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads of files the user picked, which may be any size a hostile sender
 * chose: never more than a stated limit is read, so a huge file is refused
 * rather than filling memory or the cache (SEC-8).
 */
object BoundedRead {
    private const val BUFFER_BYTES = 64 * 1024

    /** All of [input], or null if it holds more than [limit] bytes or cannot be read. */
    fun bytes(
        input: InputStream,
        limit: Int,
    ): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (copy(input, out, limit.toLong())) out.toByteArray() else null
    }

    /**
     * Copies [input] to [output]. False, having read at most [limit] + 1
     * bytes, when [input] is longer than [limit] or a read fails.
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        limit: Long,
    ): Boolean =
        try {
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            var over = false
            while (!over) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit + 1 - total).toInt())
                if (n < 0) break
                total += n
                over = total > limit
                if (!over) output.write(buffer, 0, n)
            }
            !over
        } catch (_: IOException) {
            false
        }
}
