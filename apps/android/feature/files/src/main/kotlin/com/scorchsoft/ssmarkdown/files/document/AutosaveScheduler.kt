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
 * A save that has started is never cancelled: an edit during it only
 * books another save for after it (DS-5). Cancelling it once the bytes
 * were written skipped the bookkeeping that marks the buffer clean and
 * settles the journal. Only the idle wait is a debounce.
 *
 * Time comes from [scope]'s dispatcher, so tests drive it with virtual time.
 */
@Suppress("TooManyFunctions") // Listen, schedule, save, pause and resume: one small step each.
class AutosaveScheduler(
    private val scope: CoroutineScope,
    private val store: DocumentStore,
    private val isEnabled: (DocumentUri) -> Boolean,
    private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
) {
    private val pending = HashMap<DocumentUri, Job>()
    private val paused = HashMap<DocumentUri, SaveOutcome>()
    private val saving = HashSet<DocumentUri>()
    private val again = HashSet<DocumentUri>()
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
        if (uri in saving) {
            again += uri
            return
        }
        pending.remove(uri)?.cancel()
        if (shouldSave(uri)) pending[uri] = scope.launch { saveNow(uri) }
    }

    private fun onEdited(uri: DocumentUri) {
        if (!isEnabled(uri) || uri in paused) return
        if (uri in saving) {
            again += uri // Never cancel a save in flight; save again after it.
            return
        }
        schedule(uri)
    }

    private fun schedule(uri: DocumentUri) {
        pending.remove(uri)?.cancel()
        pending[uri] =
            scope.launch {
                delay(idleMillis)
                saveNow(uri)
            }
    }

    private fun shouldSave(uri: DocumentUri): Boolean = isEnabled(uri) && uri !in paused && store[uri]?.isDirty == true

    private suspend fun saveNow(uri: DocumentUri) {
        saving += uri
        val outcome =
            try {
                store.save(uri)
            } finally {
                saving -= uri
                pending.remove(uri)
            }
        val rerun = again.remove(uri)
        if (outcome !is SaveOutcome.Saved) {
            pause(uri, outcome)
        } else if (rerun && shouldSave(uri)) {
            schedule(uri)
        }
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
        again -= uri
        if (uri !in saving) pending.remove(uri)?.cancel()
    }

    /** The default idle wait. */
    companion object {
        /** Two seconds after the last keystroke. */
        const val DEFAULT_IDLE_MILLIS: Long = 2_000
    }
}
