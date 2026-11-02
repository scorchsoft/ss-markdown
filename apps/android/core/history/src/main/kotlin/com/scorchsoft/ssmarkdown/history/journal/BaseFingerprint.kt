package com.scorchsoft.ssmarkdown.history.journal

import java.security.MessageDigest

/**
 * The identity of the file a journal's edits start from: size,
 * `lastModified` and the SHA-256 of its bytes (docs/robustness.md §2a).
 *
 * A journal is a list of edits, and edits only mean something on top of
 * the exact text they were made against. The header stores this
 * fingerprint so replay can refuse a journal whose base is not the file
 * the caller holds, instead of applying edits to the wrong text.
 *
 * [matches] compares size and hash only. `lastModified` is kept because
 * it is the cheap first check for "did the file change", but it is not
 * trusted: providers report 0 or whole seconds, and a copy or a sync
 * touches it without changing a byte.
 *
 * @property size the base file's length in bytes.
 * @property lastModified the provider's `lastModified`, in epoch millis; informational.
 */
public class BaseFingerprint(
    public val size: Long,
    public val lastModified: Long,
    sha256: ByteArray,
) {
    private val hash: ByteArray = sha256.copyOf()

    init {
        require(size >= 0) { "size must not be negative: $size" }
        require(hash.size == SHA256_BYTES) { "sha256 must be $SHA256_BYTES bytes, was ${hash.size}" }
    }

    /** A copy of the SHA-256 of the base bytes, so callers cannot change ours. */
    public val sha256: ByteArray get() = hash.copyOf()

    /** The hash as lowercase hex, for logs and file names. */
    public val sha256Hex: String get() = hash.joinToString("") { "%02x".format(it) }

    /**
     * True when [other] names the same bytes: equal size and equal hash.
     * This is the check that guards replay.
     */
    public fun matches(other: BaseFingerprint): Boolean = size == other.size && hash.contentEquals(other.hash)

    override fun equals(other: Any?): Boolean =
        other is BaseFingerprint && matches(other) && lastModified == other.lastModified

    override fun hashCode(): Int =
        (size.hashCode() * HASH_PRIME + lastModified.hashCode()) * HASH_PRIME +
            hash.contentHashCode()

    override fun toString(): String = "BaseFingerprint(size=$size, lastModified=$lastModified, sha256=$sha256Hex)"

    /** Constructors from raw bytes, so every caller hashes the same way. */
    public companion object {
        /** Length of a SHA-256 digest in bytes. */
        public const val SHA256_BYTES: Int = 32
        private const val HASH_PRIME = 31

        /** Fingerprints [bytes] as loaded from disk with the given [lastModified]. */
        public fun of(
            bytes: ByteArray,
            lastModified: Long,
        ): BaseFingerprint =
            BaseFingerprint(bytes.size.toLong(), lastModified, MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
