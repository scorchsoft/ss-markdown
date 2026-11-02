package com.scorchsoft.ssmarkdown.fixtures

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * The app tests' copy of feature/files' interleaving harness (test
 * sources are not shared between modules): each `withContext(io)` block
 * runs at once, then [afterEveryHop] runs before the suspended caller
 * resumes, which is where a keystroke lands on a phone while the store
 * waits on disk. Pair it with a queued main dispatcher.
 */
class InterleavingDispatcher : CoroutineDispatcher() {
    /** Runs after every hop, with its number (from 1). */
    var afterEveryHop: (Int) -> Unit = {}

    private var hops = 0

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val hop = ++hops
        block.run()
        afterEveryHop(hop)
    }
}
