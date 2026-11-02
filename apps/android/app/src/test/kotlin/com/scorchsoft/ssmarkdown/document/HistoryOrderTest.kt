package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.files.document.CheckpointReason
import com.scorchsoft.ssmarkdown.files.document.CheckpointResult
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.HistoryListing
import com.scorchsoft.ssmarkdown.history.versions.ReadResult
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.history.versions.VersionStore
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * edge-cases "History records racing each other" (DS-12): records made one
 * after another land in that order on a real I/O pool, so "latest" in
 * History is the newest, and a checkpoint returns only once it is durable.
 */
class HistoryOrderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `records land in the order they were made, and a checkpoint is the newest once it returns`() {
        val versions = VersionStore(temp.newFolder("history"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hook = VersionHistoryHook(versions, scope)
        val uri = DocumentUri("content://docs/note.md")
        try {
            repeat(RECORDS) { i -> hook.record(uri, "version $i\n", VersionKind.Saved) }
            val final = TextDocument(PieceTree.of("checkpoint\n")).snapshot()

            val result = runBlocking { hook.onCheckpoint(uri, final, CheckpointReason.ClosedWithoutSaving) }

            assertEquals(CheckpointResult.Recorded, result)
            val listed = versions.list(DocumentKey(uri.value)) as HistoryListing.Listed
            // Sequence numbers are given as records land: their order is the order of landing.
            val landed = listed.versions.sortedBy { it.id.seq }
            val texts = landed.map { (versions.read(DocumentKey(uri.value), it.id) as ReadResult.Text).text }
            assertEquals((0 until RECORDS).map { "version $it\n" } + "checkpoint\n", texts)
            assertEquals(VersionKind.Checkpoint, landed.last().kind)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val RECORDS = 40
    }
}
