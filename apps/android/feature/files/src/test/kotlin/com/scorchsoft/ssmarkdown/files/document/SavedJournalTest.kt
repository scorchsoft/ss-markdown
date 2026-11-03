package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a verified save leaves in the journal (review r3 M1, M2, L1, L4):
 * never a journal on the pre-save base, never a journal the launch scan
 * mistakes for unsaved edits, and never a "Recovery copies stopped" alarm
 * on a document with nothing unsaved. Writing the undo history is best
 * effort: it is worth less than any of those.
 */
class SavedJournalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = FakeDocumentProvider()
    private val uri = provider.put("content://docs/note.md", "alpha\n")

    private fun TestScope.fixture() = StoreFixture(tmp.root, provider, UnconfinedTestDispatcher(testScheduler))

    private suspend fun StoreFixture.opened(): OpenOutcome.Opened = store.open(uri) as OpenOutcome.Opened

    /** Makes every `<journal>.new` impossible to create, so no journal can be written whole or replaced. */
    private fun StoreFixture.blockNewJournalFiles(): File =
        File(journals.fileFor(uri.value).path + ".new").also { blocker ->
            blocker.mkdirs()
            File(blocker, "occupied").writeText("x")
        }

    private fun File.unblock() = deleteRecursively()

    @Test
    fun `M1 a save whose journal file cannot be rewritten leaves no pre-save journal behind`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.replace(doc.document.text.length, 0, "one")
            first.store.flushJournals()
            first.blockNewJournalFiles()

            assertTrue(first.store.save(uri) is SaveOutcome.Saved)
            assertTrue("nothing unsaved, so no alarm", doc.journalHealthy)
            first.store.flushJournals()

            val after = fixture()
            assertTrue(
                after.store
                    .scanForRecovery()
                    .offers
                    .isEmpty(),
            )
            val back = after.opened()
            assertNull(back.recovery)
            assertEquals(
                "alpha\none",
                back.document.document.text
                    .toString(),
            )
            assertFalse(back.document.isDirty)
            back.document.undo()
            assertEquals(
                "the history survived the save too",
                "alpha\n",
                back.document.document.text
                    .toString(),
            )
        }

    @Test
    fun `M1 a save after the journal broke drops the old journal and journals the next edit on the saved file`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.replace(doc.document.text.length, 0, "one")
            val blocker = first.blockNewJournalFiles()
            // A paste and its removal outgrow the text, so the journal compacts: a whole-file write that fails.
            doc.replace(doc.document.text.length, 0, "x".repeat(COMPACTING_CHARS))
            doc.replace(doc.document.text.length - COMPACTING_CHARS, COMPACTING_CHARS, "")
            assertFalse("the compaction failed, so the run is broken", doc.journalHealthy)

            assertTrue(first.store.save(uri) is SaveOutcome.Saved)

            assertTrue("saved: nothing is at risk any more", doc.journalHealthy)
            assertFalse(
                first.journals
                    .fileFor(uri.value)
                    .exists(),
            )
            blocker.unblock()
            doc.replace(doc.document.text.length, 0, "!")
            assertTrue(doc.journalHealthy)

            val after = fixture()
            val offers = after.store.scanForRecovery().offers
            assertEquals(listOf(uri), offers.map { it.uri })
            val back = after.opened()
            assertTrue(back.recovery is Recovery.Restored)
            assertEquals(
                "alpha\none!",
                back.document.document.text
                    .toString(),
            )
        }

    @Test
    fun `M1 the history write after a save is best effort and never raises the alarm`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.replace(doc.document.text.length, 0, "one")
            val target = provider.put("content://docs/copy.md", "")
            // Something unreadable where the target's journal goes: its clean journal cannot be written.
            val blocker = first.journals.fileFor(target.value)
            blocker.mkdirs()
            File(blocker, "occupied").writeText("x")

            assertTrue(first.store.saveAs(uri, target) is SaveOutcome.Saved)

            assertTrue(doc.journalHealthy)
            assertFalse(blocker.isFile)
            blocker.deleteRecursively()
            doc.replace(doc.document.text.length, 0, "!")
            val after = fixture()
            assertEquals(
                listOf(target),
                after.store
                    .scanForRecovery()
                    .offers
                    .map { it.uri },
            )
            assertEquals(
                "alpha\none!",
                (after.store.open(target) as OpenOutcome.Opened)
                    .document.document.text
                    .toString(),
            )
        }

    @Test
    fun `M2 a save whose only step is bigger than the history cap ends the journal, leaving none`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            pasteHugeAndUndo(doc)

            assertTrue(first.store.save(uri) is SaveOutcome.Saved)
            first.store.flushJournals()

            assertFalse(
                first.journals
                    .fileFor(uri.value)
                    .exists(),
            )
            val after = fixture()
            assertTrue(
                after.store
                    .scanForRecovery()
                    .offers
                    .isEmpty(),
            )
            assertNull(after.opened().recovery)
        }

    @Test
    fun `M2 save as with only an oversize step leaves no journal at the target`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            pasteHugeAndUndo(doc)
            val target = provider.put("content://docs/copy.md", "")

            assertTrue(first.store.saveAs(uri, target) is SaveOutcome.Saved)
            first.store.flushJournals()

            assertFalse(
                first.journals
                    .fileFor(target.value)
                    .exists(),
            )
            assertTrue(
                fixture()
                    .store
                    .scanForRecovery()
                    .offers
                    .isEmpty(),
            )
        }

    @Test
    fun `L1 a clean journal on an unreachable file is not unsaved edits`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.replace(doc.document.text.length, 0, "one")
            first.store.save(uri)
            first.store.flushJournals()
            assertTrue(first.journals.holdsHistoryOnly(uri.value))
            provider.revoked += uri

            val after = fixture()
            after.store.scanForRecovery()
            val failed = after.store.open(uri)

            assertTrue(failed.toString(), failed is OpenOutcome.Failed && !failed.hasUnsavedEdits)
        }

    /**
     * Leaves the document dirty with one step that is too big for the
     * journal's tail: a 4.3 M char paste, undone, so it waits as a redo
     * step and the file to save stays small.
     */
    private fun pasteHugeAndUndo(doc: OpenDocument) {
        doc.replace(doc.document.text.length, 0, HUGE_PASTE)
        doc.undo()
        assertTrue(doc.isDirty && doc.document.canRedo && !doc.document.canUndo)
    }

    private companion object {
        /** One paste whose undo step alone is over the 8 MB tail (12 + 2 bytes a char). */
        val HUGE_PASTE: String = "p".repeat(4_300_000)

        /** Journalled twice (in, then out), past the 1 MiB compaction floor and twice the text. */
        const val COMPACTING_CHARS = 600_000
    }
}
