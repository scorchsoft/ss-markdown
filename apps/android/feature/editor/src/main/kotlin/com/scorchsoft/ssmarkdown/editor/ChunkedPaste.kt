package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.text.TextDocument
import java.util.concurrent.Executor

/**
 * How far a large paste has got, for the host's progress indicator.
 *
 * @property inserted chars inserted so far.
 * @property total chars the paste will insert (after line-break normalisation).
 * @property done true once, for the last report; the paste is then one undo step.
 */
data class PasteProgress(
    val inserted: Int,
    val total: Int,
    val done: Boolean,
)

/**
 * Holds the keyboard's and hardware keys' edits while a [ChunkedPaste] is
 * running and replays them, in order, once it ends, so typing during a
 * paste is never lost, never lands in the middle of the pasted text, and
 * never joins the paste's undo step.
 */
internal class InputGate {
    private val held = ArrayList<() -> Unit>()

    /** True while edits are being held. */
    var isHolding: Boolean = false
        private set

    /** Runs [edit] now, or holds it until [release]. */
    fun run(edit: () -> Unit) {
        if (isHolding) held += edit else edit()
    }

    fun hold() {
        isHolding = true
    }

    /** Stops holding and replays what was held. */
    fun release() {
        isHolding = false
        val replay = held.toList()
        held.clear()
        replay.forEach { it() }
    }
}

/**
 * Pastes text too large for one frame (docs/edge-cases.md, "Paste of
 * 50 MB"): the clip is turned into text and its line breaks normalised on
 * [background], then inserted on the main thread in [CHUNK_CHARS] slices,
 * one per frame through [main], so drawing and the keyboard stay live.
 * Progress goes to [onProgress] after every slice.
 *
 * The whole paste is one edit group, so a single Undo removes it, and the
 * [gate] holds other edits until it ends. Slices never split a surrogate
 * pair. A paste is cancelled (what is in stays in, as one undo step) when
 * the document is replaced.
 */
internal class ChunkedPaste(
    private val session: EditingSession,
    private val gate: InputGate,
    /** Where the clip becomes text; tests swap in a direct executor. */
    var background: Executor,
    /** Where each slice goes in: the next animation frame; tests pump them by hand. */
    var main: Executor,
    /** Asked once the clip is text and its length known; false refuses the paste (PERF-G). */
    private val admit: (chars: Int) -> Boolean = { true },
    private val onProgress: (PasteProgress) -> Unit,
) {
    private var job: Job? = null
    private var preparing = false

    /** True from [start] until the last slice is in. */
    val isRunning: Boolean get() = preparing || job != null

    private class Job(
        val document: TextDocument,
        val text: String,
        var at: Int,
        var inserted: Int = 0,
    )

    /**
     * Replaces `[start, end)` with the text [source] produces. [source] runs
     * on the background executor: it is where a huge clip is coerced to text.
     */
    fun start(
        start: Int,
        end: Int,
        source: () -> CharSequence,
    ) {
        if (isRunning) return
        preparing = true
        gate.hold()
        val document = session.document
        session.finishComposingText()
        background.execute {
            val text = normalise(source())
            main.execute { begin(document, start, end, text) }
        }
    }

    /**
     * Puts the rest of a running paste in now, as the same undo step, so an
     * edit or undo from outside the keyboard can follow it: one made while
     * the paste's edit group was open crashed (undo) or landed inside the
     * pasted text (edge-cases "Undo or a command during a large paste").
     * Returns what went in now, so offsets taken before can follow it; null
     * when no slices were running (while the clip is still becoming text no
     * edit group is open and the document is untouched).
     */
    fun completeNow(): RestOfPaste? {
        val running = job ?: return null
        val at = running.at
        val rest = running.text.length - running.inserted
        val added = if (session.document === running.document && rest > 0) rest else 0
        if (added > 0) {
            session.replaceRange(at, at, running.text.substring(running.inserted))
            running.at += added
            running.inserted = running.text.length
        }
        finish(running)
        return RestOfPaste(at, added)
    }

    /** What [completeNow] inserted: [added] chars at [at]. */
    class RestOfPaste(
        val at: Int,
        val added: Int,
    ) {
        /** Where [offset], taken before the insertion, is now: the text from [at] on moved past the paste. */
        fun follow(offset: Int): Int = if (offset >= at) offset + added else offset

        /** True when `[start, end)` had [at] strictly inside it, so the paste now splits it. */
        fun splits(
            start: Int,
            end: Int,
        ): Boolean = added > 0 && at in (start + 1) until end
    }

    /** Ends a running paste where it is, keeping what is in as one undo step. */
    fun cancel() {
        val running = job ?: return
        finish(running)
    }

    private fun begin(
        document: TextDocument,
        start: Int,
        end: Int,
        text: String,
    ) {
        preparing = false
        if (session.document !== document || !admit(text.length)) {
            onProgress(PasteProgress(0, text.length, done = true))
            gate.release()
            return
        }
        val length = session.document.text.length
        val from = minOf(start, end).coerceIn(0, length)
        val to = maxOf(start, end).coerceIn(0, length)
        session.document.beginEditGroup()
        val running = Job(document, text, from)
        job = running
        if (to > from) session.replaceRange(from, to, "")
        step(running)
    }

    private fun step(running: Job) {
        if (job !== running) return
        if (session.document !== running.document) {
            finish(running)
            return
        }
        val text = running.text
        var end = minOf(text.length, running.inserted + CHUNK_CHARS)
        if (end < text.length && text[end].isLowSurrogate()) end++
        session.replaceRange(running.at, running.at, text.substring(running.inserted, end))
        running.at += end - running.inserted
        running.inserted = end
        if (end >= text.length) {
            finish(running)
        } else {
            onProgress(PasteProgress(running.inserted, text.length, done = false))
            main.execute { step(running) }
        }
    }

    private fun finish(running: Job) {
        job = null
        running.document.endEditGroup()
        if (session.document === running.document) session.setSelection(running.at, running.at)
        onProgress(PasteProgress(running.inserted, running.text.length, done = true))
        gate.release()
    }

    companion object {
        /** The paste for [view]: prepared on its injected executor, sliced per frame, progress to its listener. */
        fun forView(
            view: MarkdownCodeView,
            gate: InputGate,
        ): ChunkedPaste =
            ChunkedPaste(
                view.session,
                gate,
                { view.backgroundExecutor.execute(it) },
                { view.postOnAnimation(it) },
                admit = { view.listener?.admitPaste(it) != false },
            ) {
                view.listener?.onPasteProgress(it)
                if (it.done) view.onSelectionSettled()
            }

        /** Pastes longer than this go through [ChunkedPaste]; shorter ones apply at once. */
        const val THRESHOLD_CHARS: Int = 256 * 1024

        /** Chars inserted per frame: a few ms of piece-tree and line-index work. */
        const val CHUNK_CHARS: Int = 256 * 1024

        /** CRLF and lone CR become `'\n'`, as the document stores them; done off the main thread. */
        internal fun normalise(text: CharSequence): String {
            val plain = text.toString()
            return if (plain.indexOf('\r') < 0) plain else plain.replace("\r\n", "\n").replace('\r', '\n')
        }
    }
}
