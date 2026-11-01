package com.scorchsoft.ssmarkdown.files.fakes

import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.document.JournalExecutor
import com.scorchsoft.ssmarkdown.files.memory.MemoryGovernor
import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore
import com.scorchsoft.ssmarkdown.history.journal.Cancellable
import com.scorchsoft.ssmarkdown.history.journal.FlushScheduler
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/**
 * A store over real app-storage directories under [root] and any
 * provider. Building a second fixture over the same [root] is a process
 * restart: whatever was flushed survives, nothing else does.
 */
class StoreFixture(
    val root: File,
    val provider: DocumentProvider,
    io: CoroutineDispatcher,
    val memory: FakeMemorySource = FakeMemorySource(),
    val history: RecordingHistory = RecordingHistory(),
    val journalThread: JournalExecutor = JournalExecutor.INLINE,
) {
    // Deferred flushes never run by themselves; tests flush explicitly, as onPause does.
    private val scheduler = FlushScheduler { _, _ -> Cancellable { } }
    val journals = JournalStore(File(root, "journal"), scheduler)
    val baseCopies = BaseCopyStore(File(root, "base"))
    val governor = MemoryGovernor(memory)
    val store = DocumentStore(provider, journals, baseCopies, governor, io, history, journalThread = journalThread)
}
