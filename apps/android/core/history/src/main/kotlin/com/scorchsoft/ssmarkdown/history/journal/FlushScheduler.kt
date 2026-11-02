package com.scorchsoft.ssmarkdown.history.journal

/**
 * The time source for flush timing and journal creation stamps.
 *
 * Injected so tests run on a fake clock with no sleeping. In the app it
 * is `System.currentTimeMillis`; flush timing only uses differences and
 * delays, so a wall-clock jump can at worst move one sync by a moment.
 */
public fun interface JournalClock {
    /** Milliseconds since the epoch. */
    public fun nowMillis(): Long

    /** The real clock. */
    public companion object {
        /** `System.currentTimeMillis`. */
        public val SYSTEM: JournalClock = JournalClock { System.currentTimeMillis() }
    }
}

/**
 * Runs a deferred flush later, on the writer's thread.
 *
 * The journal never starts a thread itself. The app supplies a scheduler
 * backed by the editor's main-thread `Handler` or a single-thread
 * dispatcher; tests supply one that runs tasks when a fake clock is
 * advanced. A [JournalWriter] is not thread-safe, so the task must run on
 * the same thread that calls it.
 */
public fun interface FlushScheduler {
    /** Runs [task] after [delayMillis]; the returned handle cancels it if it has not run. */
    public fun schedule(
        delayMillis: Long,
        task: Runnable,
    ): Cancellable
}

/** A handle to a scheduled task, so a newer deadline can replace an older one. */
public fun interface Cancellable {
    /** Stops the task if it has not run yet; does nothing otherwise. */
    public fun cancel()
}
