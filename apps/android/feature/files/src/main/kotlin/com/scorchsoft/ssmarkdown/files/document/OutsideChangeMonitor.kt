package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The foreground poll (robustness §2a, "When we check"): every
 * [intervalMillis] while a document is on screen, a cheap metadata query
 * that hashes only when metadata cannot be trusted; plus checks on resume
 * and whenever a `ContentObserver` reports a change ([onContentChanged]).
 *
 * Time comes from [scope]'s dispatcher, so tests use virtual time.
 *
 * @param onResult receives every check's result, for banners.
 */
class OutsideChangeMonitor(
    private val scope: CoroutineScope,
    private val store: DocumentStore,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val onResult: (DocumentUri, OutsideChange) -> Unit = { _, _ -> },
) {
    private val watching = HashMap<DocumentUri, Job>()

    /** Polls [uri] while it is in the foreground. */
    fun watch(uri: DocumentUri) {
        if (uri in watching) return
        watching[uri] =
            scope.launch {
                while (true) {
                    delay(intervalMillis)
                    check(uri, CheckReason.Periodic)
                }
            }
    }

    /** Stops polling [uri]: it left the screen. */
    fun unwatch(uri: DocumentUri) {
        watching.remove(uri)?.cancel()
    }

    /** Back in the app or on the file. */
    fun onResume(uri: DocumentUri) {
        scope.launch { check(uri, CheckReason.Resume) }
    }

    /** A `ContentObserver` saw the provider report a change. */
    fun onContentChanged(uri: DocumentUri) {
        scope.launch { check(uri, CheckReason.Periodic) }
    }

    private suspend fun check(
        uri: DocumentUri,
        reason: CheckReason,
    ) {
        val result = store.checkOutside(uri, reason)
        if (result == OutsideChange.NotOpen) unwatch(uri) else onResult(uri, result)
    }

    /** The default poll interval. */
    companion object {
        /** "Every few seconds while the file is in the foreground." */
        const val DEFAULT_INTERVAL_MILLIS: Long = 5_000
    }
}
