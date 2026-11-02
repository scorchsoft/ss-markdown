package com.scorchsoft.ssmarkdown.history.journal

/**
 * The records appended since the last sync and the one flush scheduled
 * for them: the state a group commit is made of.
 *
 * Only one flush is ever scheduled. Each append replaces it with the
 * policy's new deadline, which is what turns "sync after a second of
 * quiet" into a debounce.
 */
internal class PendingBatch(
    private val scheduler: FlushScheduler,
) {
    private var records = 0
    private var bytes = 0L
    private var firstAt = 0L
    private var scheduled: Cancellable? = null

    /** Counts a record of [size] bytes appended at [now]; returns the batch as the policy sees it. */
    fun add(
        size: Int,
        now: Long,
    ): PendingWrites {
        if (records == 0) firstAt = now
        records++
        bytes += size
        return PendingWrites(records, bytes, now - firstAt)
    }

    /** Replaces any scheduled flush with [flush], due in [delayMillis]. */
    fun flushIn(
        delayMillis: Long,
        flush: () -> Unit,
    ) {
        scheduled?.cancel()
        scheduled = scheduler.schedule(delayMillis) { flush() }
    }

    /** Everything is durable (or superseded): forget the batch and its scheduled flush. */
    fun clear() {
        scheduled?.cancel()
        scheduled = null
        records = 0
        bytes = 0
    }
}
