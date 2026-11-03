package com.scorchsoft.ssmarkdown.fixtures

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Disk work that waits for the test: every `withContext(io)` block is
 * queued, and the test runs them in any order it likes. Taking a slow
 * copy aside with [take] and draining the rest is a phone where opening
 * another file finishes while the copy is still being written. Pair it
 * with an unconfined main dispatcher, so each block's caller resumes as
 * soon as the block has run.
 */
class QueuedDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    /** How many blocks are waiting. */
    val waiting: Int get() = queue.size

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queue.addLast(block)
    }

    /** Removes the oldest waiting block without running it; run it later with [Runnable.run]. */
    fun take(): Runnable = queue.removeFirst()

    /** Runs blocks, including any they queue, until none is waiting. */
    fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}
