package com.scorchsoft.ssmarkdown.fonts.store

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * How a [boundedCopy] ended. Read and write failures are kept apart: one
 * is the network or the document provider, the other is the disk.
 */
internal sealed interface CopyOutcome {
    data class Done(
        val bytes: Long,
    ) : CopyOutcome

    data class ReadFailed(
        val error: IOException,
    ) : CopyOutcome

    data object WriteFailed : CopyOutcome

    data object OverLimit : CopyOutcome
}

private const val BUFFER = 32 * 1024

/**
 * Copies [input] to [output] until end of stream, stopping as soon as more
 * than [limit] bytes arrive (so a hostile stream cannot fill the disk) and
 * checking for cancellation between chunks. [onChunk] sees every chunk
 * after it is written.
 */
internal suspend fun boundedCopy(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    onChunk: (ByteArray, Int) -> Unit = { _, _ -> },
): CopyOutcome {
    val buffer = ByteArray(BUFFER)
    var total = 0L
    var outcome: CopyOutcome? = null
    while (outcome == null) {
        currentCoroutineContext().ensureActive()
        val read =
            try {
                input.read(buffer)
            } catch (error: IOException) {
                outcome = CopyOutcome.ReadFailed(error)
                continue
            }
        total += maxOf(read, 0)
        outcome =
            when {
                read < 0 -> CopyOutcome.Done(total)
                total > limit -> CopyOutcome.OverLimit
                !write(output, buffer, read) -> CopyOutcome.WriteFailed
                else -> null.also { onChunk(buffer, read) }
            }
    }
    return outcome
}

private fun write(
    output: OutputStream,
    buffer: ByteArray,
    count: Int,
): Boolean =
    try {
        output.write(buffer, 0, count)
        true
    } catch (_: IOException) {
        false
    }
