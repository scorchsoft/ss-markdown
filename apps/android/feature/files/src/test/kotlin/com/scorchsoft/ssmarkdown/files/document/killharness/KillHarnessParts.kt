package com.scorchsoft.ssmarkdown.files.document.killharness

import com.scorchsoft.ssmarkdown.files.document.CheckpointReason
import com.scorchsoft.ssmarkdown.files.document.CheckpointResult
import com.scorchsoft.ssmarkdown.files.document.HistoryHook
import com.scorchsoft.ssmarkdown.files.document.JournalExecutor
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.history.journal.Cancellable
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.RecordResult
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.history.versions.VersionStore
import com.scorchsoft.ssmarkdown.text.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Where the simulated process dies. An [Error], not an exception, so no
 * `catch (e: Exception)` in the code under test can swallow it, exactly
 * as nothing in a process can catch `SIGKILL`.
 */
internal class Killed : Error("killed")

/** Model time for the whole run: journal flush timers, History stamps and the loss measurement. */
internal class ManualClock(
    var now: Long = 1_700_000_000_000,
)

/**
 * The main thread, as a queue the harness steps one task at a time, so a
 * kill can land between any two of them (after a disk hop, before the
 * store's code resumes).
 */
internal class ManualMain : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    val isEmpty: Boolean get() = queue.isEmpty()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queue.addLast(block)
    }

    /** Runs the next task; false when there is none. */
    fun runOne(): Boolean {
        val task = queue.removeFirstOrNull() ?: return false
        task.run()
        return true
    }
}

/**
 * The journal thread, as a queue the harness drains: tasks run only when
 * the harness lets them, so a kill can leave work queued (the thread
 * behind the main thread, as it can be on a phone), and the group-commit
 * timers fire at their own model time.
 */
internal class ManualJournalThread(
    private val clock: ManualClock,
) : JournalExecutor {
    private class Timer(
        val due: Long,
        val order: Long,
        val task: Runnable,
    ) {
        var cancelled = false
    }

    private val queue = ArrayDeque<Runnable>()
    private val timers = ArrayList<Timer>()
    private var order = 0L

    /** Runs after each task, for the harness to count events and watch the files. */
    var afterTask: () -> Unit = {}

    val isEmpty: Boolean get() = queue.isEmpty()

    override fun execute(task: Runnable) {
        queue.addLast(task)
    }

    override fun drain(timeoutMillis: Long): Boolean {
        while (runOne()) Unit
        return true
    }

    override fun schedule(
        delayMillis: Long,
        task: Runnable,
    ): Cancellable {
        val timer = Timer(clock.now + delayMillis, order++, task)
        timers += timer
        return Cancellable { timer.cancelled = true }
    }

    /** Runs the next queued task; false when there is none. */
    fun runOne(): Boolean {
        val task = queue.removeFirstOrNull() ?: return false
        task.run()
        afterTask()
        return true
    }

    /** The timer due next at or before [limit], removed from the list; null if none. */
    fun takeDueTimer(limit: Long): Runnable? {
        timers.removeAll { it.cancelled }
        val next = timers.filter { it.due <= limit }.minWithOrNull(compareBy({ it.due }, { it.order })) ?: return null
        timers.remove(next)
        clock.now = maxOf(clock.now, next.due)
        return next.task
    }
}

/**
 * History over the real [VersionStore], recording inline: what the app's
 * `VersionHistoryHook` does on its queue, minus the queue.
 */
internal class InlineVersionHook(
    private val versions: VersionStore,
) : HistoryHook {
    override fun onOpened(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    ) {
        versions.recordOpened(DocumentKey(uri.value), fingerprint.sha256Hex) { text.toString() }
    }

    override fun onSaved(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    ) {
        versions.record(DocumentKey(uri.value), text.toString(), VersionKind.Saved)
    }

    override fun onChangedOutside(
        uri: DocumentUri,
        text: Snapshot,
        fingerprint: BaseFingerprint,
    ) {
        versions.record(DocumentKey(uri.value), text.toString(), VersionKind.ChangedOutside)
    }

    override suspend fun onCheckpoint(
        uri: DocumentUri,
        text: Snapshot,
        reason: CheckpointReason,
    ): CheckpointResult =
        when (versions.record(DocumentKey(uri.value), text.toString(), VersionKind.Checkpoint, reason.name)) {
            is RecordResult.Recorded, is RecordResult.Unchanged -> CheckpointResult.Recorded
            RecordResult.Off -> CheckpointResult.Off
            else -> CheckpointResult.Failed
        }
}
