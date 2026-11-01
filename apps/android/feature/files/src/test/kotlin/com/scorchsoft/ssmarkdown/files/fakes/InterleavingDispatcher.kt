package com.scorchsoft.ssmarkdown.files.fakes

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * An I/O dispatcher for interleaving tests: it runs each `withContext(io)`
 * block at once, on the caller's thread, and then runs whatever the test
 * hooked to that hop **before** the suspended caller resumes.
 *
 * On a phone, `MarkdownCodeView` edits the document on the main looper
 * while a store coroutine waits in `withContext(io)`. A hook here is that
 * keystroke: it lands after the disk work and before the store's code
 * after the suspension, which is exactly the window the never-lose-work
 * races live in. Use it with a queued main dispatcher
 * (`StandardTestDispatcher`, the `runTest` default), so the caller's
 * resumption waits in the queue while the hook runs.
 *
 * Hops are numbered from 1 in the order they happen. [hops] lists what
 * ran, so a test that needs "the third hop" can check it is hooking the
 * one it means.
 */
class InterleavingDispatcher : CoroutineDispatcher() {
    private val afterHop = HashMap<Int, MutableList<() -> Unit>>()

    /** How many `withContext(io)` blocks have run so far. */
    var hops: Int = 0
        private set

    /** Runs [action] right after hop [n] (1-based, counting from now on), before its caller resumes. */
    fun afterHop(
        n: Int,
        action: () -> Unit,
    ) {
        afterHop.getOrPut(hops + n) { mutableListOf() } += action
    }

    /** Runs [action] after every hop from now on. */
    var afterEveryHop: (Int) -> Unit = {}

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val hop = ++hops
        block.run()
        afterHop.remove(hop)?.forEach { it() }
        afterEveryHop(hop)
    }
}
