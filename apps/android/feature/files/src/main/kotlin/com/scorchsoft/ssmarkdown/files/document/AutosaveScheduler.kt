package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Autosave (robustness §3a): after [idleMillis] without an edit, and at
 * once when leaving the file or the app.
 *
 * It **pauses** a document after any save that does not succeed (a
 * conflict, lost permission, a full disk, a failed verify) and stays
 * paused until [resume]: it never retries its way over someone else's
 * change. Whether a document autosaves at all is [isEnabled]'s call:
 * the global mode, a per-file override, and always for drafts.
 *
 * Time comes from [scope]'s dispatcher, so tests drive it with virtual time.
 */
class AutosaveScheduler(
    private val scope: CoroutineScope,
    private val store: DocumentStore,
    private val isEnabled: (DocumentUri) -> Boolean,
    private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
) {
    private val pending = HashMap<DocumentUri, Job>()
    private val paused = HashMap<DocumentUri, SaveOutcome>()
    private val listener: (DocumentEvent) -> Unit = { event ->
        when (event) {
            is DocumentEvent.Edited -> onEdited(event.uri)
            is DocumentEvent.ConflictDetected -> pauseForConflict(event.uri)
            else -> Unit
        }
    }

    /** Starts listening to the store's edits. */
    fun start() = store.addListener(listener)

    /** Stops listening and cancels pending saves. */
    fun stop() {
        store.removeListener(listener)
        pending.values.forEach(Job::cancel)
        pending.clear()
    }

    /** Why autosave is paused for [uri], or null if it is not. Drives the "Autosave paused" banner. */
    fun pausedBy(uri: DocumentUri): SaveOutcome? = paused[uri]

    /** The user dealt with the cause (resolved the conflict, picked Save as): autosave again. */
    fun resume(uri: DocumentUri) {
        paused.remove(uri)
    }

    /** Leaving the file or the app: save now rather than after the idle wait. */
    fun onLeave(uri: DocumentUri) {
        pending.remove(uri)?.cancel()
        if (shouldSave(uri)) pending[uri] = scope.launch { saveNow(uri) }
    }

    private fun onEdited(uri: DocumentUri) {
        if (!isEnabled(uri) || uri in paused) return
        pending.remove(uri)?.cancel()
        pending[uri] =
            scope.launch {
                delay(idleMillis)
                saveNow(uri)
            }
    }

    private fun shouldSave(uri: DocumentUri): Boolean = isEnabled(uri) && uri !in paused && store[uri]?.isDirty == true

    private suspend fun saveNow(uri: DocumentUri) {
        val outcome = store.save(uri)
        pending.remove(uri)
        if (outcome !is SaveOutcome.Saved) pause(uri, outcome)
    }

    private fun pauseForConflict(uri: DocumentUri) {
        val theirs = store[uri]?.conflict?.theirsFingerprint ?: return
        pause(uri, SaveOutcome.Conflict(theirs))
    }

    private fun pause(
        uri: DocumentUri,
        outcome: SaveOutcome,
    ) {
        paused[uri] = outcome
        pending.remove(uri)?.cancel()
    }

    /** The default idle wait. */
    companion object {
        /** Two seconds after the last keystroke. */
        const val DEFAULT_IDLE_MILLIS: Long = 2_000
    }
}
