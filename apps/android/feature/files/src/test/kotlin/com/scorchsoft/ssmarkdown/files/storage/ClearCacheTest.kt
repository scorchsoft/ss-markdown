package com.scorchsoft.ssmarkdown.files.storage

import com.scorchsoft.ssmarkdown.files.document.OpenOutcome
import com.scorchsoft.ssmarkdown.files.document.Recovery
import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * edge-cases "User runs Android Clear cache": Android deletes everything in
 * `cacheDir`, perhaps while the app is stopped with unsaved edits. Nothing
 * of value may be there: the unsaved edits come back after a restart, and
 * history, drafts and fonts are untouched. The store runs over the very
 * directories [StorageLayout] gives the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClearCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `clearing the cache while edits are unsaved loses nothing`() =
        runTest {
            val files = tmp.newFolder("files")
            val noBackup = tmp.newFolder("no_backup")
            val cache = tmp.newFolder("cache")
            val layout = StorageLayout(files, noBackup, cache)
            val provider = FakeDocumentProvider()
            val uri = provider.put("content://docs/note.md", "# Note\n")
            val io = UnconfinedTestDispatcher(testScheduler)

            // StoreFixture puts the journal and base copies under its root, exactly where the layout does.
            val before = StoreFixture(noBackup, provider, io)
            assertEquals(layout.journals, File(noBackup, "journal"))
            assertEquals(layout.baseCopies, File(noBackup, "base"))
            val doc = (before.store.open(uri) as OpenOutcome.Opened).document
            doc.replace(doc.document.text.length, 0, "Unsaved line.\n")
            before.store.flushJournals() // onStop; then Settings → Clear cache, then a cold start.

            val kept =
                listOf(layout.history, layout.drafts, layout.fonts).map { dir ->
                    File(dir.apply { mkdirs() }, "kept.bin").apply { writeText("value") }
                }
            File(layout.previewCache.apply { mkdirs() }, "diagram.svg").writeText("<svg/>")
            File(File(cache, "shared").apply { mkdirs() }, "big.md").writeText("shared copy")

            assertTrue(cache.deleteRecursively()) // What Clear cache does.

            val after = StoreFixture(noBackup, provider, io)
            assertEquals(
                listOf(uri),
                after.store
                    .scanForRecovery()
                    .offers
                    .map { it.uri },
            )
            val reopened = after.store.open(uri) as OpenOutcome.Opened
            assertTrue(reopened.recovery is Recovery.Restored)
            assertEquals(
                "# Note\nUnsaved line.\n",
                reopened.document.document.text
                    .toString(),
            )
            kept.forEach { assertEquals(it.path, "value", it.readText()) }
            assertFalse(layout.previewCache.exists())
        }
}
