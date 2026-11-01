package com.scorchsoft.ssmarkdown.files.memory

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Thrown inside a load when memory runs out part way; the loader turns it into a decline. */
internal class LoadAbortedForMemory : IOException("load stopped: memory pressure")

/**
 * Polls the governor every [pollEveryBytes] while a file streams in, so a
 * load that admission allowed but the device cannot actually afford stops
 * cleanly instead of ending in `OutOfMemoryError` (robustness Part 2a).
 * When shedding does not relieve the pressure, reading fails with
 * [LoadAbortedForMemory].
 */
internal class GovernedInputStream(
    input: InputStream,
    private val governor: MemoryGovernor,
    private val pollEveryBytes: Long = DEFAULT_POLL_BYTES,
) : FilterInputStream(input) {
    private var sinceLastPoll = 0L

    override fun read(): Int = super.read().also { if (it >= 0) account(1) }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = super.read(b, off, len).also { if (it > 0) account(it.toLong()) }

    private fun account(bytes: Long) {
        sinceLastPoll += bytes
        if (sinceLastPoll < pollEveryBytes) return
        sinceLastPoll = 0
        if (governor.underPressure()) {
            governor.checkPressure()
            if (governor.underPressure()) throw LoadAbortedForMemory()
        }
    }

    private companion object {
        const val DEFAULT_POLL_BYTES = 4L * 1024 * 1024
    }
}
