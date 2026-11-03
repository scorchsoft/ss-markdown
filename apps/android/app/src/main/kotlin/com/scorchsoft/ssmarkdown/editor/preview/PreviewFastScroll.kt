package com.scorchsoft.ssmarkdown.editor.preview

import android.view.Choreographer
import com.scorchsoft.ssmarkdown.core.ui.components.FastScrollState
import com.scorchsoft.ssmarkdown.document.OutlineTracker
import com.scorchsoft.ssmarkdown.editor.panes.SectionBubble
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage

/** Runs a callback once on the next frame; [Choreographer] in the app, a hand-cranked one in tests. */
fun interface FrameScheduler {
    /** Calls [frame] once, on the next frame. */
    fun postFrame(frame: Runnable)

    companion object {
        /** The display's frame clock: one callback object, reused, so posting allocates nothing. */
        fun choreographer(): FrameScheduler {
            var pending: Runnable? = null
            val callback = Choreographer.FrameCallback { pending?.run() }
            return FrameScheduler { frame ->
                pending = frame
                Choreographer.getInstance().postFrameCallback(callback)
            }
        }
    }
}

/**
 * The Preview pane's fast-scroll thumb (docs/ux-spec.md, "Fast scroll").
 * The page reports where it is and how much of it shows with every top
 * line it reports; a drag sends the page the fraction to scroll to, at
 * most one message a frame, the latest one winning. The page's scroll then
 * reports a top line like any other, which moves the anchor and, in Split,
 * the Code pane: the thumb never drives the other pane itself.
 *
 * The bubble names the heading above the page's top line; with no heading
 * there is no bubble (a line number means little in Preview).
 */
class PreviewFastScroll(
    private val send: (HostMessage) -> Unit,
    private val outline: () -> OutlineTracker?,
) {
    /** Where a drag's message waits for the next frame; tests swap in their own. */
    var frames: FrameScheduler = FrameScheduler.choreographer()

    /** What the thumb draws. */
    val state = FastScrollState()

    private val bubble = SectionBubble(state, lineFallback = null)
    private var visible = 1f
    private var topLine = 0
    private var pending = NONE
    private var posted = false
    private val flush = Runnable { sendPending() }

    /** Messages sent to the page by drags; tests read it. */
    var sent: Int = 0
        private set

    /** The page reported how much of it shows. */
    fun onVisibleFraction(fraction: Float) {
        visible = fraction
    }

    /** The page scrolled to [fraction] of its range: shows the thumb if the reader is the one scrolling. */
    fun onScrollFraction(fraction: Float) = state.onScrolled(fraction, visible)

    /** The page reported [line] at its top: the bubble follows it while the thumb is held. */
    fun onTopLine(line: Int) {
        topLine = line
        if (state.isDragging) bubble.update(outline(), line)
    }

    /** The thumb was taken: the bubble starts on the section the page is at. */
    fun begin() = bubble.update(outline(), topLine)

    /** The page has a selection ([active]): its handles and toolbar own the edge, so the thumb keeps away. */
    fun onSelection(active: Boolean) {
        state.suppressed = active
    }

    /** The thumb is at [fraction]: the page goes there on the next frame. */
    fun dragTo(fraction: Float) {
        pending = fraction
        if (posted) return
        posted = true
        frames.postFrame(flush)
    }

    /** The thumb was let go: the last position still reaches the page; the page stays there. */
    fun end() {
        if (pending != NONE && !posted) sendPending()
    }

    private fun sendPending() {
        posted = false
        val fraction = pending
        if (fraction == NONE) return
        pending = NONE
        sent++
        send(HostMessage.ScrollToFraction(fraction))
    }

    private companion object {
        const val NONE = -1f
    }
}
