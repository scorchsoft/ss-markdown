package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.markdown.Heading
import com.scorchsoft.ssmarkdown.markdown.IncrementalTokenizer
import com.scorchsoft.ssmarkdown.markdown.LineSource
import com.scorchsoft.ssmarkdown.markdown.Outline
import com.scorchsoft.ssmarkdown.markdown.asLineSource
import com.scorchsoft.ssmarkdown.text.EditObserver
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.text.Snapshot
import com.scorchsoft.ssmarkdown.text.TextDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where the outline works and where it reports back.
 *
 * @property worker runs the outline tokenizer; any background dispatcher (its passes never overlap).
 * @property main the main thread's scope: published outlines are installed there.
 */
class OutlineThreads(
    val worker: CoroutineDispatcher,
    val main: CoroutineScope,
)

/**
 * The document's heading index for the Navigator, the breadcrumb and
 * "Copy section", kept current from every edit to the buffer.
 *
 * The tokenizing runs **on a background snapshot** (docs/robustness.md,
 * "Lazy, with a time budget"; performance review PERF-D). Before, it ran
 * on the main thread in 4 ms slices beside the Code pane's 8 ms, so a
 * fence opened at the top of a 20 MB file could put 12 ms of tokenising
 * into a frame for seconds. Now the main thread only:
 *
 * - hears each op as it is applied (an [EditObserver], so the Code view,
 *   the strip, Find/Replace, undo and Preview edits all count) and turns
 *   it into a line change, O(log n);
 * - hands the worker the changes and an immutable [Snapshot] once a run
 *   of edits has landed ([flush]);
 * - installs the [Outline] copies the worker publishes, replaying any
 *   edits made since that copy's text, so heading line numbers always
 *   match the text on screen.
 *
 * Every edit is incremental (PERF-A). Before, an edit made with no Code
 * view on screen (Preview-only mode) invalidated every line, emptied the
 * Navigator and the breadcrumb, and re-tokenised the whole file.
 *
 * The worker's tokenizer keeps block states only, about ten bytes a line,
 * and no tokens: the outline never draws (performance review PERF-9).
 */
class OutlineTracker(
    private val document: TextDocument,
    private val threads: OutlineThreads,
    /** Called on the main thread when a new outline is installed. */
    private val onChanged: () -> Unit,
) {
    // Main thread.
    private val tree = document.text
    private val pending = ArrayList<LineEdit>()
    private val unpublished = ArrayDeque<LineEdit>()
    private var sequence = 0L

    @Volatile private var closed = false

    // Worker; handed over under [lock], so each run sees the last one's writes.
    private val source = SnapshotSource(document.snapshot())
    private val tokenizer = IncrementalTokenizer(source, tokenCacheLines = 0)
    private var appliedSequence = 0L
    private var lastPublishNanos = 0L

    private val lock = Any()
    private val inbox = ArrayList<Handoff>()
    private var running = false

    /** The outline the main thread answers from. */
    private var shown: Outline = tokenizer.outline.copy()

    private val observer =
        object : EditObserver {
            override fun beforeEdit(op: EditOp) = record(op)
        }

    @Volatile private var tokenized = 0L

    /** Lines tokenised by the worker so far: the statistic the PERF-A test counts. */
    val linesTokenized: Long get() = tokenized

    /** Lines in the text, for the reading-position line. */
    val lineCount: Int get() = tree.lineCount

    /** Starts listening and tokenising. Call once, on the main thread. */
    fun start() {
        document.addEditObserver(observer)
        submit(Handoff(source.snapshot, emptyList(), sequence))
    }

    /** A run of edits has landed: hand them to the worker with the text they produced. Main thread. */
    fun flush() {
        if (closed || pending.isEmpty()) return
        val edits = pending.toList()
        pending.clear()
        submit(Handoff(document.snapshot(), edits, sequence))
    }

    /** Stops listening; the worker stops at its next check. */
    fun close() {
        closed = true
        document.removeEditObserver(observer)
    }

    /** Every heading, in document order. */
    fun headings(): List<Heading> = shown.headings()

    /** The headings containing [line], outermost first. */
    fun breadcrumbAt(line: Int): List<Heading> = shown.breadcrumbAt(line)

    /** The line range of the section headed on [headingLine], or null. */
    fun sectionRange(headingLine: Int): IntRange? = shown.sectionRange(headingLine)

    private fun record(op: EditOp) {
        val first = tree.positionAt(op.offset).line
        val edit = LineEdit(++sequence, first, breaks(op.deleted) + 1, breaks(op.inserted) + 1)
        pending += edit
        unpublished.addLast(edit)
        // Keep line numbers valid for the text on screen until the worker's copy arrives.
        shown.followEdit(edit.from, edit.removed, edit.inserted)
    }

    private fun submit(handoff: Handoff) {
        synchronized(lock) {
            inbox += handoff
            if (running) return
            running = true
        }
        threads.main.launch(threads.worker) { work() }
    }

    /** The worker's loop: apply what arrived, tokenise a slice, publish now and then, until done. */
    private fun work() {
        var dirty = false
        while (true) {
            val batch =
                synchronized(lock) {
                    if (closed || (inbox.isEmpty() && tokenizer.isComplete && !dirty)) {
                        running = false
                        return
                    }
                    val taken = inbox.toList()
                    inbox.clear()
                    taken
                }
            // Outside the lock: the main thread only ever waits for the inbox, never for a slice.
            batch.forEach(::apply)
            if (batch.isNotEmpty()) dirty = true
            if (!tokenizer.isComplete) {
                tokenizer.continueInIdle(SLICE_NANOS)
                dirty = true
            }
            tokenized = tokenizer.linesTokenized
            val now = System.nanoTime()
            if (dirty && (tokenizer.isComplete || now - lastPublishNanos >= PUBLISH_INTERVAL_NANOS)) {
                publish(tokenizer.outline.copy(), appliedSequence)
                lastPublishNanos = now
                dirty = false
            }
        }
    }

    private fun apply(handoff: Handoff) {
        handoff.edits.forEach { tokenizer.invalidate(it.from, it.removed, it.inserted) }
        source.snapshot = handoff.snapshot
        appliedSequence = handoff.sequence
    }

    private fun publish(
        copy: Outline,
        upTo: Long,
    ) {
        threads.main.launch { install(copy, upTo) }
    }

    /** Main thread: the worker's copy reflects edits up to [upTo]; replay the later ones onto it. */
    private fun install(
        copy: Outline,
        upTo: Long,
    ) {
        if (closed) return
        while (unpublished.isNotEmpty() && unpublished.first().sequence <= upTo) unpublished.removeFirst()
        unpublished.forEach { copy.followEdit(it.from, it.removed, it.inserted) }
        shown = copy
        onChanged()
    }

    /** Lines `[from, from + removed)` became [inserted] lines; edit number [sequence]. */
    private class LineEdit(
        val sequence: Long,
        val from: Int,
        val removed: Int,
        val inserted: Int,
    )

    /** What the worker gets: the text after [edits], and the number of the last of them. */
    private class Handoff(
        val snapshot: Snapshot,
        val edits: List<LineEdit>,
        val sequence: Long,
    )

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

        /** The text stores `'\n'` only (core:text normalises), so a line break is one char. */
        fun breaks(text: CharSequence): Int {
            var count = 0
            for (i in text.indices) if (text[i] == '\n') count++
            return count
        }
    }
}
