package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.markdown.IncrementalTokenizer
import com.scorchsoft.ssmarkdown.markdown.LineSource
import com.scorchsoft.ssmarkdown.markdown.LineTokenizer
import com.scorchsoft.ssmarkdown.markdown.Outline
import com.scorchsoft.ssmarkdown.markdown.asLineSource
import com.scorchsoft.ssmarkdown.text.Snapshot
import kotlinx.coroutines.launch

/** Lines `[from, from + removed)` became [inserted] lines; edit number [sequence]. */
internal class LineEdit(
    val sequence: Long,
    val from: Int,
    val removed: Int,
    val inserted: Int,
)

/** What the worker gets: the text after [edits], and the number of the last of them. */
internal class Handoff(
    val snapshot: Snapshot,
    val edits: List<LineEdit>,
    val sequence: Long,
)

/**
 * The background half of [OutlineTracker] (performance review PERF-D):
 * an outline tokenizer over the latest [Snapshot] it was handed, run on
 * [DocumentThreads.worker] in slices, publishing [Outline] copies now and
 * then through [publish] (called on the worker; the tracker moves them
 * to the main thread).
 *
 * Only the inbox is shared, under a lock the main thread holds for an
 * append and nothing more: it never waits for a slice. The tokenizer is
 * touched by one run at a time; runs are handed over under the same lock,
 * so each sees the last one's writes.
 */
internal class OutlineWorker(
    initial: Snapshot,
    private val threads: DocumentThreads,
    private val publish: (copy: Outline, upTo: Long) -> Unit,
) {
    private val source = SnapshotSource(initial)
    private val tokenizer = IncrementalTokenizer(source, LineTokenizer(highlightCode = false), tokenCacheLines = 0)
    private var appliedSequence = 0L
    private var lastPublishNanos = 0L
    private var dirty = false

    private val lock = Any()
    private val inbox = ArrayList<Handoff>()
    private var running = false

    @Volatile private var stopped = false

    @Volatile private var tokenized = 0L

    /** Lines tokenised so far: the statistic the PERF-A test counts. */
    val linesTokenized: Long get() = tokenized

    /** The (empty) outline before the first pass, for the main thread to start from. */
    fun emptyOutline(): Outline = tokenizer.outline.copy()

    /** Queues [handoff] and starts a run if none is going. Main thread. */
    fun submit(handoff: Handoff) {
        synchronized(lock) {
            inbox += handoff
            if (running) return
            running = true
        }
        threads.main.launch(threads.worker) { run() }
    }

    /** The run in progress stops at its next look at the inbox. */
    fun stop() {
        stopped = true
    }

    /** Apply what arrived, tokenise a slice, publish now and then, until there is nothing left to do. */
    private fun run() {
        while (true) {
            val batch = takeBatchOrFinish() ?: return
            // Outside the lock: the main thread only ever waits for the inbox, never for a slice.
            batch.forEach(::apply)
            if (batch.isNotEmpty()) dirty = true
            if (!tokenizer.isComplete) {
                tokenizer.continueInIdle(SLICE_NANOS)
                dirty = true
            }
            tokenized = tokenizer.linesTokenized
            publishIfDue()
        }
    }

    /** The waiting handoffs, or null (and the run over) when stopped or idle. */
    private fun takeBatchOrFinish(): List<Handoff>? =
        synchronized(lock) {
            if (stopped || isIdle()) {
                running = false
                return null
            }
            inbox.toList().also { inbox.clear() }
        }

    /** Nothing queued, nothing to tokenise, and nothing unpublished. */
    private fun isIdle(): Boolean = inbox.isEmpty() && tokenizer.isComplete && !dirty

    private fun publishIfDue() {
        if (!dirty) return
        val now = System.nanoTime()
        if (tokenizer.isComplete || now - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
            publish(tokenizer.outline.copy(), appliedSequence)
            lastPublishNanos = now
            dirty = false
        }
    }

    private fun apply(handoff: Handoff) {
        handoff.edits.forEach { tokenizer.invalidate(it.from, it.removed, it.inserted) }
        source.snapshot = handoff.snapshot
        appliedSequence = handoff.sequence
    }

    /** The latest snapshot the worker was handed, as lines. */
    private class SnapshotSource(
        snapshot: Snapshot,
    ) : LineSource {
        private var lines = snapshot.asLineSource()

        var snapshot: Snapshot = snapshot
            set(value) {
                field = value
                lines = value.asLineSource()
            }

        override val lineCount: Int get() = lines.lineCount

        override fun lineText(line: Int): CharSequence = lines.lineText(line)
    }

    private companion object {
        /** Between looks at the inbox, so a new edit waits at most this long on a busy walk. */
        const val SLICE_NANOS = 8_000_000L

        /** A long walk shows its progress this often; a short one publishes once, when done. */
        const val PUBLISH_INTERVAL_NANOS = 100_000_000L
    }
}
