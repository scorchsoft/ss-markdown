package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fingerprint.DiskProbe
import com.scorchsoft.ssmarkdown.files.memory.MemoryGovernor
import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore
import com.scorchsoft.ssmarkdown.files.save.SavePipeline
import com.scorchsoft.ssmarkdown.files.save.SaveTimeout
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import com.scorchsoft.ssmarkdown.text.DecodeResult
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.text.Utf8Decoder
import kotlinx.coroutines.CoroutineDispatcher
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * What the store's collaborators share: the dependencies, the open
 * entries and the listeners. Internal plumbing, so [DocumentStore] stays
 * a readable facade and each flow (open, save, outside change) gets its
 * own small class.
 */
@Suppress("LongParameterList") // Constructor injection without a DI framework (docs/engineering.md).
internal class StoreContext(
    val provider: DocumentProvider,
    val journals: JournalStore,
    val baseCopies: BaseCopyStore,
    val governor: MemoryGovernor,
    val io: CoroutineDispatcher,
    val history: HistoryHook,
    val cursorMapper: CursorMapper,
    saveTimeout: SaveTimeout,
    val journalThread: JournalExecutor,
    private val callbacks: Executor,
) {
    val entries = LinkedHashMap<DocumentUri, OpenDocument>()
    val listeners = CopyOnWriteArrayList<(DocumentEvent) -> Unit>()
    val loader = DocumentLoader(provider, governor)
    val probe = DiskProbe(provider)
    val pipeline = SavePipeline(provider, baseCopies, saveTimeout)

    /** Edit times from the last launch scan, for the recovery banner. */
    val journalTimes = HashMap<DocumentUri, Long>()

    fun notify(event: DocumentEvent) = listeners.forEach { it(event) }

    /** A journal link for [uri]; its health changes reach listeners on the owner thread. */
    fun journalFor(uri: DocumentUri) =
        JournalLink(journals, uri.value, journalThread, onHealthChanged = {
            callbacks.execute { notify(DocumentEvent.JournalHealthChanged(uri)) }
        })

    /**
     * Records [entry]'s buffer in History and waits until it is durable,
     * again if typing moved it meanwhile, so the version kept is the one
     * about to be replaced; the caller replaces it in the same step as
     * this returns. Null when the buffer was still moving after the last
     * attempt: the newest keystrokes are in no checkpoint, so the caller
     * must not replace or drop the buffer (R2-DS-5).
     */
    suspend fun checkpointSettled(
        entry: OpenDocument,
        reason: CheckpointReason,
    ): CheckpointResult? {
        repeat(MAX_CHECKPOINT_ATTEMPTS) {
            val version = entry.document.version
            val result = history.onCheckpoint(entry.uri, entry.document.snapshot(), reason)
            if (entry.document.version == version) return result
        }
        return null
    }

    /**
     * The base copy's text, if the copy holds exactly [base]'s bytes and
     * they are valid UTF-8. Blocking.
     */
    fun readBaseText(
        uri: DocumentUri,
        base: BaseFingerprint,
    ): TextDocument? {
        if (!baseCopies.matches(uri, base)) return null
        val decoded =
            try {
                baseCopies.openInput(uri)?.use(Utf8Decoder::decode)
            } catch (_: IOException) {
                null
            }
        return (decoded as? DecodeResult.Decoded)?.let(TextDocument::from)
    }
}

/** Continuous typing could move the buffer forever; after this many checkpoints the step is asked again. */
private const val MAX_CHECKPOINT_ATTEMPTS = 3

/**
 * Edits [document] into [target] with one replace over the span that
 * differs, so a mixed-line-ending file keeps the endings of every line
 * outside that span, and the step can be undone.
 */
internal fun TextDocument.replaceAllWith(target: CharSequence) {
    val current = text
    var prefix = 0
    val shorter = minOf(current.length, target.length)
    while (prefix < shorter && current[prefix] == target[prefix]) prefix++
    var suffix = 0
    while (suffix < shorter - prefix &&
        current[current.length - 1 - suffix] == target[target.length - 1 - suffix]
    ) {
        suffix++
    }
    val removed = current.length - prefix - suffix
    val inserted = target.subSequence(prefix, target.length - suffix)
    if (removed > 0 || inserted.isNotEmpty()) replace(prefix, removed, inserted)
}
