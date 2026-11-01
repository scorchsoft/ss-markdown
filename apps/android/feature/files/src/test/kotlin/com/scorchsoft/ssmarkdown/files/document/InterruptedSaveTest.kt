package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.InterleavingDispatcher
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.files.save.SavePipeline
import com.scorchsoft.ssmarkdown.files.save.SaveRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A process killed in the middle of a save (docs/robustness.md §2, the
 * kill-test cases the random harness found): the next launch's scan
 * leaves the file old or new, never torn, never touches an outside edit,
 * and brings the journal's edits back with no false conflict.
 */
class InterruptedSaveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Dead : Error("killed")

    private val provider = FakeDocumentProvider()
    private val uri: DocumentUri = provider.put("content://docs/note.md", OLD)
    private val root: File by lazy { tmp.newFolder() }

    private fun fixture() = StoreFixture(root, provider, InterleavingDispatcher())

    /** Opens, types [typed] at the end, and dies at byte [killAt] of the save's write. */
    private suspend fun dieMidSave(
        typed: String,
        killAt: Int,
    ): StoreFixture {
        val before = fixture()
        val doc = (before.store.open(uri) as OpenOutcome.Opened).document
        doc.replace(doc.document.text.length, 0, typed)
        var written = 0
        provider.onWriteByte = { if (written++ == killAt) throw Dead() }
        try {
            before.store.save(uri)
            error("the save was meant to die")
        } catch (_: Dead) {
            // The process is gone.
        }
        provider.onWriteByte = null
        return before
    }

    private suspend fun relaunch(): Pair<RecoveryScan, OpenOutcome.Opened> {
        val after = fixture()
        val scan = after.store.scanForRecovery()
        return scan to after.store.open(uri) as OpenOutcome.Opened
    }

    @Test
    fun `a write torn part way is put back to the old bytes and the edits come back from the journal`() =
        runTest {
            dieMidSave(" typed", killAt = 7)
            assertTrue("torn on disk", provider.text(uri).let { it != OLD && it != OLD + " typed" })

            val (scan, reopened) = relaunch()

            assertEquals(1, scan.repairedSaves)
            assertEquals(OLD, provider.text(uri))
            assertTrue(reopened.recovery is Recovery.Restored)
            assertEquals(
                OLD + " typed",
                reopened.document.document.text
                    .toString(),
            )
        }

    @Test
    fun `a shorter write over a provider that ignored truncation, old tail and all, is put back too`() =
        runTest {
            provider.ignoreTruncation = true
            val before = fixture()
            val doc = (before.store.open(uri) as OpenOutcome.Opened).document
            doc.replace(0, "# Note\n\n".length, "") // Shorter: the old tail would outlive the write.
            var written = 0
            provider.onWriteByte = { if (written++ == 12) throw Dead() }
            try {
                before.store.save(uri)
            } catch (_: Dead) {
                // The process is gone.
            }
            provider.onWriteByte = null
            provider.ignoreTruncation = false
            assertTrue(provider.text(uri) != OLD)

            val (scan, reopened) = relaunch()

            assertEquals(1, scan.repairedSaves)
            assertEquals(OLD, provider.text(uri))
            assertEquals(
                OLD.removePrefix("# Note\n\n"),
                reopened.document.document.text
                    .toString(),
            )
        }

    @Test
    fun `a write that finished before the journal moved opens clean on the saved version, with no conflict`() =
        runTest {
            val before = fixture()
            val doc = (before.store.open(uri) as OpenOutcome.Opened).document
            doc.replace(doc.document.text.length, 0, " saved")
            before.store.flushJournals()
            // The store's own pipeline writes and verifies, then the process dies before its bookkeeping.
            val request = SaveRequest(uri, doc.document.snapshot(), doc.document.format, doc.base)
            assertTrue(SavePipeline(provider, BaseCopyStore(File(root, "base"))).save(request) is SaveOutcome.Saved)

            val (scan, reopened) = relaunch()

            assertEquals(0, scan.repairedSaves)
            assertEquals(OLD + " saved", provider.text(uri))
            assertNull("a save of ours is not a change made outside", reopened.document.conflict)
            assertEquals(
                OLD + " saved",
                reopened.document.document.text
                    .toString(),
            )
            assertTrue("every edit is in the saved file", !reopened.document.isDirty)
        }

    @Test
    fun `edits typed after a save that finished before the journal moved are rebased onto it`() =
        runTest {
            val before = fixture()
            val doc = (before.store.open(uri) as OpenOutcome.Opened).document
            doc.replace(doc.document.text.length, 0, " saved")
            val request = SaveRequest(uri, doc.document.snapshot(), doc.document.format, doc.base)
            assertTrue(SavePipeline(provider, BaseCopyStore(File(root, "base"))).save(request) is SaveOutcome.Saved)
            // Typed while the save ran: journalled on the old base.
            doc.replace(doc.document.text.length, 0, " and more")
            before.store.flushJournals()

            val (_, reopened) = relaunch()

            assertNull(reopened.document.conflict)
            assertTrue(reopened.recovery is Recovery.Restored)
            assertEquals(
                OLD + " saved and more",
                reopened.document.document.text
                    .toString(),
            )
            assertTrue(reopened.document.isDirty)
        }

    @Test
    fun `an outside edit made after the crash is never overwritten, and becomes a conflict`() =
        runTest {
            dieMidSave(" typed", killAt = 7)
            provider.externalWrite(uri, "written by another app after the crash\n".toByteArray())

            val (scan, reopened) = relaunch()

            assertEquals(0, scan.repairedSaves)
            assertEquals("written by another app after the crash\n", provider.text(uri))
            assertTrue(reopened.recovery is Recovery.Conflicted)
            assertEquals(
                OLD + " typed",
                reopened.document.document.text
                    .toString(),
            )
        }

    @Test
    fun `a file that cannot be reached at launch keeps its record for the next one`() =
        runTest {
            dieMidSave(" typed", killAt = 7)
            provider.revoked += uri
            fixture().store.scanForRecovery()
            assertTrue(BaseCopyStore(File(root, "base")).pendingSaves.find(uri) != null)

            provider.revoked -= uri
            val (scan, _) = relaunch()
            assertEquals(1, scan.repairedSaves)
            assertEquals(OLD, provider.text(uri))
        }

    private companion object {
        const val OLD = "# Note\n\nthe saved version, long enough to tear\n"
    }
}
