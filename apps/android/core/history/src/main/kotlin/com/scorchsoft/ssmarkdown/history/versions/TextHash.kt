package com.scorchsoft.ssmarkdown.history.versions

import com.scorchsoft.ssmarkdown.history.journal.Wtf8
import java.security.MessageDigest

/**
 * How version history turns text into bytes and hashes: WTF-8, so every
 * `String` (lone surrogates included) round-trips exactly, and SHA-256
 * over those bytes.
 */
internal object TextHash {
    fun bytes(text: String): ByteArray = Wtf8.encode(text)

    fun text(bytes: ByteArray): String? = Wtf8.decode(bytes)

    fun sha256Hex(text: String): String = sha256Hex(bytes(text))

    fun sha256Hex(bytes: ByteArray): String = IndexCodec.bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** The most WTF-8 bytes a text of [length] UTF-16 units can need. */
    fun maxBytes(length: Int): Int = (length.toLong() * BYTES_PER_UNIT).coerceAtMost(MAX_ARRAY).toInt()

    private const val BYTES_PER_UNIT = 3L
    private const val MAX_ARRAY = Int.MAX_VALUE - 8L
}
