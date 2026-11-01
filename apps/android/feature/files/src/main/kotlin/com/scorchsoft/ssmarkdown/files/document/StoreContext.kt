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
) {
    val entries = LinkedHashMap<DocumentUri, OpenDocument>()
    val listeners = CopyOnWriteArrayList<(DocumentEvent) -> Unit>()
    val loader = DocumentLoader(provider, governor)
    val probe = DiskProbe(provider)
    val pipeline = SavePipeline(provider, baseCopies, saveTimeout)

    /** Edit times from the last launch scan, for the recovery banner. */
    val journalTimes = HashMap<DocumentUri, Long>()

    fun notify(event: DocumentEvent) = listeners.forEach { it(event) }

    fun journalFor(uri: DocumentUri) = JournalLink(journals, uri.value)

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
