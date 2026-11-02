package com.scorchsoft.ssmarkdown.history.journal

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * The byte path under a [JournalWriter]: an in-memory buffer, the file,
 * and the one rule that keeps the file replayable after a failed write.
 *
 * That rule: a write that fails may have landed part of a record. Left
 * alone, that fragment would sit in the middle of the file once later
 * records were appended after it, and replay would stop at it and lose
 * them all. So a failed write is cut back to the last whole batch
 * ([committedLength]) before anything else is written. If even the cut
 * fails, nothing more is appended until the file is rewritten by
 * compaction ([needsRewrite]).
 */
internal class JournalAppender(
    private val file: File,
    private val sinkFactory: JournalSink.Factory,
) {
    private val buffer = ByteArrayOutputStream()
    private var sink: JournalSink? = null
    private var committedLength = -1L
    private var unsynced = false

    /** True when the file may end in a fragment and must be rewritten before appending. */
    var needsRewrite: Boolean = false
        private set

    /** Bytes held in memory, not yet handed to the file. */
    val bufferedBytes: Int get() = buffer.size()

    /** True when nothing is waiting in memory or in the OS cache. */
    val isDurable: Boolean get() = buffer.size() == 0 && !unsynced

    /** Holds a framed record in memory. */
    fun add(record: ByteArray) = buffer.write(record)

    /** Hands buffered bytes to the file without syncing; null on success. */
    fun writeOut(): IOException? =
        when {
            buffer.size() == 0 -> {
                null
            }

            needsRewrite -> {
                IOException("journal tail is damaged; compaction must rewrite it")
            }

            else -> {
                try {
                    openSink().write(buffer.toByteArray(), buffer.size())
                    committedLength += buffer.size()
                    buffer.reset()
                    unsynced = true
                    null
                } catch (e: IOException) {
                    cutBack()
                    e
                }
            }
        }

    /** Writes out and `fsync`s; null on success. */
    fun sync(): IOException? {
        val failure = writeOut()
        if (failure != null || !unsynced) return failure
        return try {
            openSink().force()
            unsynced = false
            null
        } catch (e: IOException) {
            e
        }
    }

    /**
     * Forgets buffered bytes and the open file after [file] was replaced
     * wholesale (by compaction), which also clears [needsRewrite].
     */
    fun replaced() {
        closeSink()
        buffer.reset()
        unsynced = false
        needsRewrite = false
    }

    /** Releases the file. Buffered bytes are dropped; sync first to keep them. */
    fun close() {
        closeSink()
        buffer.reset()
    }

    private fun openSink(): JournalSink =
        sink ?: sinkFactory.open(file).also {
            sink = it
            committedLength = it.size
        }

    private fun cutBack() {
        val open = sink ?: return
        try {
            open.truncate(committedLength)
        } catch (ignored: IOException) {
            needsRewrite = true
        }
    }

    private fun closeSink() {
        try {
            sink?.close()
        } catch (ignored: IOException) {
            // Closing after a successful sync loses nothing; after a failed one the data is already reported.
        }
        sink = null
    }
}
