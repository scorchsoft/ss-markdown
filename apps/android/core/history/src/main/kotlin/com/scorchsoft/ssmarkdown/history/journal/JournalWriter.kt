package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import java.io.File
import java.io.IOException

/** What a write to the journal came to. Disk trouble is expected on phones, so it is a value. */
public sealed interface JournalWriteResult {
    /**
     * Accepted. After an append, [seq] is the new record's number; after a
     * flush or close, the last record written.
     *
     * @property seq a record sequence number, or 0 if the journal holds none yet.
     */
    public data class Written(
        public val seq: Long,
    ) : JournalWriteResult

    /** The record is over [JournalFormat.MAX_PAYLOAD_BYTES]. Nothing was written. */
    public data object TooLarge : JournalWriteResult

    /**
     * The disk refused. Appended records are still held in memory and the
     * next flush retries them; the caller should warn and keep the text.
     *
     * @property cause the underlying failure.
     */
    public data class Failed(
        public val cause: IOException,
    ) : JournalWriteResult

    /** The writer was closed. */
    public data object Closed : JournalWriteResult
}

/**
 * Appends edits to one document's journal and makes them durable in
 * groups (docs/robustness.md §1).
 *
 * Appends go to memory first. The [FlushPolicy] picks when they are
 * synced; [flush] syncs now, and the app calls it on pause, before a save
 * or a view switch and before opening another file. Worst case, a crash
 * loses the edits since the last sync: about a second of typing.
 *
 * Not thread-safe: call it, and run the [FlushScheduler]'s tasks, on one
 * thread. Obtain one from [JournalStore].
 */
@Suppress("TooManyFunctions") // One append per record kind, plus flush, compact and close.
public class JournalWriter internal constructor(
    private val file: File,
    private val header: JournalHeader,
    private val appender: JournalAppender,
    firstSeq: Long,
    private val timing: FlushTiming,
) {
    private var nextSeq = firstSeq
    private val pending = PendingBatch(timing.scheduler)
    private var closed = false

    /** The document this journal belongs to. */
    public val documentKey: String get() = header.documentKey

    /** The newest record appended, durable or not; 0 when there is none. */
    public val lastSeq: Long get() = nextSeq - 1

    /** The newest record known to be on disk after an `fsync`. */
    public var durableSeq: Long = firstSeq - 1
        private set

    /** The last disk failure, including one from a scheduled flush; null once a sync succeeds. */
    public var lastFailure: IOException? = null
        private set

    /** Appends one edit. See [JournalWriteResult.Failed] for what a failure means. */
    public fun append(edit: TextEdit): JournalWriteResult = appendRecord(JournalRecord.Op(edit))

    /** Appends a [MarkerKind] marker with a short [label], for recovery and history to show. */
    public fun mark(
        kind: MarkerKind,
        label: String = "",
    ): JournalWriteResult = appendRecord(JournalRecord.Marker(kind, label))

    /**
     * The editor's undo stack closed its newest step: the next edit
     * appended starts a new one (FORMAT.md, "Undo history").
     */
    public fun endStep(): JournalWriteResult = appendRecord(JournalRecord.Step(StepKind.End))

    /** The next [opCount] edits appended are an undo of the newest step, not new edits. */
    public fun undoStep(opCount: Int): JournalWriteResult {
        require(opCount > 0) { "an undo step has at least one edit" }
        return appendRecord(JournalRecord.Step(StepKind.Undo, opCount))
    }

    /** The next [opCount] edits appended are a redo of the next undone step, not new edits. */
    public fun redoStep(opCount: Int): JournalWriteResult {
        require(opCount > 0) { "a redo step has at least one edit" }
        return appendRecord(JournalRecord.Step(StepKind.Redo, opCount))
    }

    /**
     * Writes everything appended so far to the file and, when [sync] is
     * true (the default), `fsync`s it. `sync = false` only hands bytes to
     * the OS, which survives an app crash but not a power cut.
     */
    public fun flush(sync: Boolean = true): JournalWriteResult {
        if (closed) return JournalWriteResult.Closed
        val failure = if (sync) appender.sync() else appender.writeOut()
        if (failure == null && sync) markDurable(lastSeq)
        return failure?.let(::failed) ?: JournalWriteResult.Written(lastSeq)
    }

    /**
     * Replaces the journal with one Snapshot of [currentText], so replay
     * stays fast however long the session. [currentText] must be the text
     * every appended edit has produced: it supersedes them all.
     *
     * The new file is written beside the old one and renamed over it
     * ([AtomicFile]), so a crash at any point leaves either the old
     * journal or the new one, both complete. On failure the old journal
     * and the writer carry on unchanged. Compaction also repairs a
     * journal whose tail a failed write left damaged.
     */
    public fun compact(currentText: String): JournalWriteResult {
        val snapshot = if (closed) null else RecordCodec.encode(JournalRecord.Snapshot(currentText), nextSeq)
        return when {
            closed -> JournalWriteResult.Closed
            snapshot == null -> JournalWriteResult.TooLarge
            else -> replaceWith(snapshot)
        }
    }

    /** Syncs and releases the file. Further calls return [JournalWriteResult.Closed]. */
    public fun close(): JournalWriteResult {
        val result = flush(sync = true)
        if (!closed) {
            pending.clear()
            appender.close()
            closed = true
        }
        return result
    }

    private fun appendRecord(record: JournalRecord): JournalWriteResult {
        val bytes = if (closed) null else RecordCodec.encode(record, nextSeq)
        return when {
            closed -> JournalWriteResult.Closed
            bytes == null -> JournalWriteResult.TooLarge
            else -> accept(bytes)
        }
    }

    private fun accept(bytes: ByteArray): JournalWriteResult {
        val seq = nextSeq++
        appender.add(bytes)
        val waiting = pending.add(bytes.size, timing.clock.nowMillis())
        // Keep memory bounded during a long burst; this is a write, not a sync.
        if (appender.bufferedBytes >= WRITE_THROUGH_BYTES) appender.writeOut()?.let { lastFailure = it }
        val delay = timing.policy.flushDelayMillis(waiting)
        if (delay > 0) {
            pending.flushIn(delay) { flush(sync = true) }
        } else {
            val flushed = flush(sync = true)
            if (flushed is JournalWriteResult.Failed) return flushed
        }
        return JournalWriteResult.Written(seq)
    }

    private fun replaceWith(snapshot: ByteArray): JournalWriteResult =
        try {
            AtomicFile(file).write(HeaderCodec.encode(header), snapshot)
            appender.replaced()
            markDurable(nextSeq++)
            JournalWriteResult.Written(durableSeq)
        } catch (e: IOException) {
            failed(e)
        }

    private fun markDurable(seq: Long) {
        pending.clear()
        durableSeq = seq
        lastFailure = null
    }

    private fun failed(cause: IOException): JournalWriteResult {
        lastFailure = cause
        return JournalWriteResult.Failed(cause)
    }

    private companion object {
        const val WRITE_THROUGH_BYTES = 64 * 1024
    }
}

/** The injected collaborators that decide when a writer syncs. */
internal class FlushTiming(
    val clock: JournalClock,
    val scheduler: FlushScheduler,
    val policy: FlushPolicy,
)
