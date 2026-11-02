package com.scorchsoft.ssmarkdown.files.fingerprint

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Length and SHA-256 of a byte stream: what a [com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint]
 * is made of, minus `lastModified`.
 *
 * @property size bytes seen.
 */
class ByteDigest(
    val size: Long,
    sha256: ByteArray,
) {
    private val hash = sha256.copyOf()

    /** A copy of the digest. */
    val sha256: ByteArray get() = hash.copyOf()

    /** True when [other] has the same length and hash. */
    fun sameBytesAs(other: ByteDigest): Boolean = size == other.size && hash.contentEquals(other.hash)

    override fun equals(other: Any?): Boolean = other is ByteDigest && sameBytesAs(other)

    override fun hashCode(): Int = size.hashCode() * HASH_PRIME + hash.contentHashCode()

    /** Streaming constructors. */
    companion object {
        private const val HASH_PRIME = 31
        private const val BUFFER_BYTES = 64 * 1024

        /** Digests [bytes]. */
        fun of(bytes: ByteArray): ByteDigest = ByteDigest(bytes.size.toLong(), sha256().digest(bytes))

        /**
         * Reads [input] to its end, hashing as it goes and copying every
         * byte to [copyTo] when given. One pass, constant memory, however
         * large the file.
         */
        fun of(
            input: InputStream,
            copyTo: OutputStream? = null,
        ): ByteDigest {
            val digest = sha256()
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                copyTo?.write(buffer, 0, read)
                total += read
            }
            return ByteDigest(total, digest.digest())
        }

        /** A fresh SHA-256 digest. */
        fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }
}
