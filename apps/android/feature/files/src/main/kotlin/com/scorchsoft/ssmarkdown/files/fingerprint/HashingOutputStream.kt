package com.scorchsoft.ssmarkdown.files.fingerprint

import java.io.OutputStream

/**
 * Forwards to every stream in [targets] while counting and hashing, so a
 * save can write the document, stage the new base copy and know exactly
 * what it wrote, in one pass over the text.
 *
 * Closing it does not close the targets; their owners do that, because
 * where a close fails matters (a provider reports upload failure there).
 */
internal class HashingOutputStream(
    private vararg val targets: OutputStream,
) : OutputStream() {
    private val digest = ByteDigest.sha256()
    private var count = 0L

    override fun write(b: Int) {
        digest.update(b.toByte())
        count++
        targets.forEach { it.write(b) }
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        digest.update(b, off, len)
        count += len
        targets.forEach { it.write(b, off, len) }
    }

    override fun flush() = targets.forEach { it.flush() }

    /** What was written so far. Call once, after the last write. */
    fun result(): ByteDigest = ByteDigest(count, digest.digest())
}
