package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.markdown.Heading
import com.scorchsoft.ssmarkdown.markdown.Outline
import com.scorchsoft.ssmarkdown.text.EditObserver
import com.scorchsoft.ssmarkdown.text.EditOp
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
 * - hands the worker the changes and an immutable `Snapshot` once a run
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
    // Main thread only.
    private val tree = document.text
    private val pending = ArrayList<LineEdit>()
    private val unpublished = ArrayDeque<LineEdit>()
    private var sequence = 0L
    private var closed = false
    private val initial = document.snapshot()
    private val worker = OutlineWorker(initial, threads) { copy, upTo -> threads.main.launch { install(copy, upTo) } }

    /** The outline the main thread answers from. */
    private var shown: Outline = worker.emptyOutline()

    private val observer =
        object : EditObserver {
            override fun beforeEdit(op: EditOp) = record(op)
        }

    /** Lines tokenised by the worker so far: the statistic the PERF-A test counts. */
    val linesTokenized: Long get() = worker.linesTokenized

    /** Lines in the text, for the reading-position line. */
    val lineCount: Int get() = tree.lineCount

    /** Starts listening and tokenising. Call once, on the main thread. */
    fun start() {
        document.addEditObserver(observer)
        worker.submit(Handoff(initial, emptyList(), sequence))
    }

    /** A run of edits has landed: hand them to the worker with the text they produced. Main thread. */
    fun flush() {
        if (closed || pending.isEmpty()) return
        val edits = pending.toList()
        pending.clear()
        worker.submit(Handoff(document.snapshot(), edits, sequence))
    }

    /** Stops listening; the worker stops at its next check. */
    fun close() {
        closed = true
        worker.stop()
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

    private companion object {
        /** The text stores `'\n'` only (core:text normalises), so a line break is one char. */
        fun breaks(text: CharSequence): Int {
            var count = 0
            for (i in text.indices) if (text[i] == '\n') count++
            return count
        }
    }
}
