package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.core.perf.PerfTrace
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage

/**
 * Traces "Preview refresh after typing" (quality-bar §4, < 150 ms) for the
 * emulator benchmarks: one [PerfTrace.PREVIEW_REFRESH] section per host
 * edit, from the moment it is sent to the page until the page reports a
 * render at that revision or later. The page debounces typing, so the
 * section for the last keystroke of a burst is the refresh the user waits
 * for after typing stops.
 *
 * Revisions only grow, so a render at `rev` closes every edit up to it.
 * At most [MAX_PENDING] are tracked; older ones are closed, never leaked.
 */
internal class RefreshTrace(
    private val begin: (Int) -> Unit = { PerfTrace.beginAsync(PerfTrace.PREVIEW_REFRESH, it) },
    private val end: (Int) -> Unit = { PerfTrace.endAsync(PerfTrace.PREVIEW_REFRESH, it) },
) {
    private val pending = ArrayDeque<Int>()

    /** [message] is on its way to the page. */
    fun onSent(message: HostMessage) {
        if (message !is HostMessage.Edit) return
        synchronized(pending) {
            pending.addLast(message.rev)
            begin(message.rev)
            while (pending.size > MAX_PENDING) end(pending.removeFirst())
        }
    }

    /** The page drew revision [rev]. */
    fun onRendered(rev: Int) {
        synchronized(pending) {
            while (pending.isNotEmpty() && pending.first() <= rev) end(pending.removeFirst())
        }
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}
