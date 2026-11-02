package com.scorchsoft.ssmarkdown.preview

/** What the preview should do after its renderer process died. */
sealed interface RecoveryAction {
    /**
     * Recreate the WebView and reload at the same anchor. [lean] after an
     * out-of-memory kill: diagrams wait for a tap and caches shrink.
     */
    data class Restart(
        val lean: Boolean,
    ) : RecoveryAction

    /** Too many deaths in a short time: stop and show "Preview paused" with Retry. */
    data object Pause : RecoveryAction
}

/**
 * Decides how to recover from `onRenderProcessGone` (docs/robustness.md,
 * "WebView renderer recovery"). A real crash restarts normally; a kill by the
 * system for memory (`didCrash() == false`) restarts lean. Two deaths within a
 * minute pause the preview rather than loop: something in the document is
 * killing it, and Code still works.
 *
 * Pure and clock-injected, so the policy is tested without a WebView.
 */
class RendererRecovery(
    private val windowMillis: Long = WINDOW_MILLIS,
    private val maxDeaths: Int = MAX_DEATHS_PER_WINDOW,
) {
    private val deaths = ArrayDeque<Long>()

    /** Lean mode sticks for the rest of the session once memory has run out. */
    var lean: Boolean = false
        private set

    fun onRendererGone(
        nowMillis: Long,
        didCrash: Boolean,
    ): RecoveryAction {
        deaths.addLast(nowMillis)
        while (deaths.isNotEmpty() && nowMillis - deaths.first() > windowMillis) deaths.removeFirst()
        if (!didCrash) lean = true
        return if (deaths.size >= maxDeaths) RecoveryAction.Pause else RecoveryAction.Restart(lean)
    }

    /** The user tapped Retry on "Preview paused". */
    fun reset() {
        deaths.clear()
    }

    private companion object {
        const val WINDOW_MILLIS = 60_000L
        const val MAX_DEATHS_PER_WINDOW = 2
    }
}

/** The renderer's state as the UI shows it. */
enum class RendererState {
    /** Normal. */
    RUNNING,

    /** Recovered from a crash or kill: show "Preview restarted" once. */
    RESTARTED,

    /** Gave up after repeated deaths: show "Preview paused" with Retry. */
    PAUSED,
}
