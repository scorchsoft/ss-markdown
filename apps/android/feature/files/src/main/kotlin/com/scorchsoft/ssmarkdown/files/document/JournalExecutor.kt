package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.history.journal.Cancellable
import com.scorchsoft.ssmarkdown.history.journal.FlushScheduler
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The one thread every journal write runs on (docs/robustness.md §1).
 *
 * The journal used to be written from the main thread, and from an I/O
 * thread while a save finished: an `fsync` stalled typing, and two
 * threads raced on a writer documented as single-threaded (PERF-6,
 * PERF-7). Now the main thread only *queues* journal work, in the order
 * the edits happened, and this executor runs it in that order. Ordering
 * is decided where the edits are, on the main thread, so no interleaving
 * of keystrokes with disk work can reorder or lose one; the disk work
 * itself never blocks a keystroke.
 *
 * The executor is also the journal's [FlushScheduler], so the
 * group-commit timer runs on the same thread as the writes.
 */
interface JournalExecutor : FlushScheduler {
    /** Runs [task] after every task queued before it. Never blocks on the task. */
    fun execute(task: Runnable)

    /**
     * Blocks the caller until everything queued so far has run, for at
     * most [timeoutMillis]. Returns false on timeout. Used only on
     * `onPause`/`onStop`, where Android expects state to be made durable
     * before the process may be killed.
     */
    fun drain(timeoutMillis: Long): Boolean

    /** The executors. */
    companion object {
        /**
         * Runs every task at once, on the caller's thread, and never runs a
         * scheduled flush by itself: tests flush explicitly, as `onPause`
         * does. Also what a store built without a journal thread uses.
         */
        val INLINE: JournalExecutor =
            object : JournalExecutor {
                override fun execute(task: Runnable) = task.run()

                override fun drain(timeoutMillis: Long): Boolean = true

                override fun schedule(
                    delayMillis: Long,
                    task: Runnable,
                ): Cancellable = Cancellable { }
            }
    }
}

/**
 * A [JournalExecutor] backed by one dedicated daemon thread: the app's.
 * A task that throws is contained by the journal link that queued it, so
 * the thread itself never dies.
 */
class DedicatedJournalThread(
    name: String = "ssmarkdown-journal",
) : JournalExecutor {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

    override fun execute(task: Runnable) {
        executor.execute(task)
    }

    override fun schedule(
        delayMillis: Long,
        task: Runnable,
    ): Cancellable {
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }

    override fun drain(timeoutMillis: Long): Boolean {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        return try {
            done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

/**
 * Runs [block] on the journal thread after everything queued before it,
 * and returns its result to the caller. Cancelling the caller does not
 * stop [block]: journal work, once queued, always runs.
 */
internal suspend fun <T> JournalExecutor.call(block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        execute {
            val result = runCatching(block)
            if (continuation.isActive) continuation.resumeWith(result)
        }
    }
