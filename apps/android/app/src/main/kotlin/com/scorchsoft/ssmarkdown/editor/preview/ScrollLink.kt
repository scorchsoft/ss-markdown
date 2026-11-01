package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.ViewMode

/**
 * Which pane's scrolling is the anchor (ux-spec "Position continuity"):
 * in Code or Preview it is the pane on screen; in Split it is the pane
 * last touched, and with "Scroll panes together" on the other pane
 * follows it. Only the leader's reports move the anchor, so a follower
 * scrolling into place can never bounce the leader back.
 *
 * Pure, so the sync rules are host-JVM tests.
 */
class ScrollLink {
    /** The pane that leads in Split; Code until the preview is touched. */
    var splitLeader: Pane = Pane.Code
        private set

    /** A finger went down on [pane]. */
    fun touched(pane: Pane) {
        splitLeader = pane
    }

    /** The pane whose top line is the anchor in [mode]. */
    fun leader(mode: ViewMode): Pane =
        when (mode) {
            ViewMode.Code -> Pane.Code
            ViewMode.Preview -> Pane.Preview
            ViewMode.Split -> splitLeader
        }

    /**
     * [pane] reported [line] at its top. Returns what to do: whether the
     * anchor moves, and which pane (if any) must scroll to follow.
     */
    fun onTopLine(
        pane: Pane,
        mode: ViewMode,
        linked: Boolean,
    ): Follow {
        if (pane != leader(mode)) return Follow.Ignore
        val other = if (pane == Pane.Code) Pane.Preview else Pane.Code
        return if (mode == ViewMode.Split && linked) Follow.Anchor(other) else Follow.Anchor(null)
    }

    /** The outcome of a top-line report. */
    sealed interface Follow {
        /** A follower reporting its own scroll: nothing changes. */
        data object Ignore : Follow

        /** The anchor moves; [follower] scrolls to it, if any. */
        data class Anchor(
            val follower: Pane?,
        ) : Follow
    }
}
