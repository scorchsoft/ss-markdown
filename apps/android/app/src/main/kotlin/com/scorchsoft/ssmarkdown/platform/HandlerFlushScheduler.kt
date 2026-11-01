package com.scorchsoft.ssmarkdown.platform

import android.os.Handler
import com.scorchsoft.ssmarkdown.history.journal.Cancellable
import com.scorchsoft.ssmarkdown.history.journal.FlushScheduler

/**
 * The journal's group-commit timer on an Android [Handler]: the ~1 s idle
 * fsync of docs/robustness.md §1. The handler's looper is the thread the
 * journal is written from (the main thread), so a flush never races an append.
 */
class HandlerFlushScheduler(
    private val handler: Handler,
) : FlushScheduler {
    override fun schedule(
        delayMillis: Long,
        task: Runnable,
    ): Cancellable {
        handler.postDelayed(task, delayMillis)
        return Cancellable { handler.removeCallbacks(task) }
    }
}
