package com.scorchsoft.ssmarkdown.document

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Where a document's background upkeep runs, and where it reports back:
 * the outline's tokenizer ([OutlineTracker]) and idle compaction
 * ([TreeCompactor]).
 *
 * @property worker any background dispatcher; each job serialises its own runs.
 * @property main the main thread's scope: results are applied there.
 */
class DocumentThreads(
    val worker: CoroutineDispatcher,
    val main: CoroutineScope,
)
