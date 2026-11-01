package com.scorchsoft.ssmarkdown.files.save

import java.io.IOException
import java.io.OutputStream

/**
 * How long a save may take before it gives up (docs/edge-cases.md, "Slow
 * cloud provider"). A timed-out save ends as [SaveOutcome.TimedOut]: the
 * UI offers Retry or Save locally, the document stays dirty and its
 * journal is untouched.
 *
 * The deadline is enforced where giving up is safe:
 *
 * - after the precondition read and backup, before anything is written,
 *   so the file is [FileIntegrity.Untouched];
 * - between the bytes of the write, which then stops and the backup is
 *   written back, so the file is the old version, never a partial one.
 *
 * It is **not** enforced inside a single provider call (a cloud provider
 * that uploads in `close()`), nor on the read-back once every byte has
 * been written: abandoning a call half way is exactly how a partial file
 * would be left, which is the one thing a timeout must never do.
 *
 * @property millis the budget for the precondition and the write; [Long.MAX_VALUE] means none.
 * @property clock monotonic milliseconds; injected so tests control time.
 */
class SaveTimeout(
    val millis: Long = DEFAULT_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    init {
        require(millis > 0) { "a save timeout must be positive, was $millis" }
    }

    /** Starts the clock for one save. */
    internal fun start(): SaveDeadline =
        SaveDeadline(
            clock,
            if (millis ==
                Long.MAX_VALUE
            ) {
                Long.MAX_VALUE
            } else {
                clock() + millis
            },
        )

    companion object {
        /** Long enough for a large file on a slow mobile upload; short enough that nobody waits forever. */
        const val DEFAULT_MILLIS: Long = 30_000

        /** Never times out: for local files and tests. */
        val NONE: SaveTimeout = SaveTimeout(Long.MAX_VALUE)

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** One save's deadline. */
internal class SaveDeadline(
    private val clock: () -> Long,
    private val at: Long,
) {
    val expired: Boolean get() = at != Long.MAX_VALUE && clock() >= at

    /** Throws [SaveTimedOut] if the deadline has passed: after the last write, before the file is closed. */
    fun checkpoint() {
        if (expired) throw SaveTimedOut()
    }

    /** [stream] that throws [SaveTimedOut] on the first write after the deadline. */
    fun guard(stream: OutputStream): OutputStream =
        if (at == Long.MAX_VALUE) {
            stream
        } else {
            object : OutputStream() {
                override fun write(b: Int) {
                    check()
                    stream.write(b)
                }

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    check()
                    stream.write(b, off, len)
                }

                override fun flush() = stream.flush()

                override fun close() = stream.close()

                private fun check() {
                    if (expired) throw SaveTimedOut()
                }
            }
        }
}

/** Thrown inside a write to stop it at the deadline; classified as `ProviderResult.TimedOut`. */
internal class SaveTimedOut : IOException("save timed out")
