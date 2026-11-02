package com.scorchsoft.ssmarkdown.history.journal

/**
 * What has been appended but not yet made durable, as a [FlushPolicy]
 * sees it.
 *
 * @property records records appended since the last sync.
 * @property bytes their framed size in bytes.
 * @property millisSinceFirst how long the oldest of them has waited.
 */
public data class PendingWrites(
    public val records: Int,
    public val bytes: Long,
    public val millisSinceFirst: Long,
)

/**
 * Decides when appended records are synced to disk: group commit.
 *
 * An `fsync` per keystroke would wear flash and stall typing, so records
 * are batched and synced together. The policy is an interface so the
 * trade-off between "the last second of typing" and battery can change,
 * and so tests can drive it without real time. Explicit flushes
 * ([JournalWriter.flush]: on pause, before a save, a view switch or
 * opening another file) happen whatever the policy says.
 */
public fun interface FlushPolicy {
    /**
     * Called after every append. Returns how many milliseconds from now
     * the pending records must be synced; 0 means sync now. A later
     * append replaces the deadline, which is what makes a debounce.
     */
    public fun flushDelayMillis(pending: PendingWrites): Long
}

/**
 * The default policy (docs/robustness.md §1): sync after [idleMillis] of
 * quiet, so a pause in typing makes everything durable within about a
 * second.
 *
 * Two caps stop an idle-only rule from waiting forever: someone typing
 * without a pause is synced every [maxLatencyMillis], and a large paste
 * over [maxPendingBytes] is synced straight away, because losing it would
 * be the most expensive loss of all.
 *
 * @property idleMillis quiet time before a sync.
 * @property maxLatencyMillis longest any record waits during continuous typing.
 * @property maxPendingBytes pending size that triggers an immediate sync.
 */
public class IdleFlushPolicy(
    public val idleMillis: Long = DEFAULT_IDLE_MILLIS,
    public val maxLatencyMillis: Long = DEFAULT_MAX_LATENCY_MILLIS,
    public val maxPendingBytes: Long = DEFAULT_MAX_PENDING_BYTES,
) : FlushPolicy {
    init {
        require(idleMillis >= 0 && maxLatencyMillis >= 0 && maxPendingBytes >= 0) { "limits must not be negative" }
    }

    override fun flushDelayMillis(pending: PendingWrites): Long =
        if (pending.bytes >= maxPendingBytes) {
            0
        } else {
            minOf(idleMillis, (maxLatencyMillis - pending.millisSinceFirst).coerceAtLeast(0))
        }

    /** The defaults, named so the app and tests can refer to them. */
    public companion object {
        /** About a second of idle typing, as VS Code's backup tracker does. */
        public const val DEFAULT_IDLE_MILLIS: Long = 1_000

        /**
         * Continuous typing is still synced at least this often. One second,
         * like the idle delay, so a kill loses at most the last second of
         * typing whether or not the user paused: the bound the random kill
         * test measures (rubric #1). It was 5 s, which let a kill during
         * steady typing lose up to five seconds. The cost is one small
         * `fsync` a second while typing.
         */
        public const val DEFAULT_MAX_LATENCY_MILLIS: Long = 1_000

        /** 256 KiB: far beyond typing, so only pastes and replace-alls hit it. */
        public const val DEFAULT_MAX_PENDING_BYTES: Long = 256L * 1024
    }
}
