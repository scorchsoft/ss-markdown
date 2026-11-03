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

/**
 * Undo and Redo survive process death while the document is open
 * (docs/adr/0016-undo-survives-process-death.md): across recovery, a save,
 * repeated deaths; and they are discarded on an outside change, a close
 * and "Don't save".
 *
 * A "death" here is what `onPause` leaves: journals flushed, then a new
 * store over the same folders, as in [DataSafetyRound2Test].
 */
class UndoAcrossDeathTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = FakeDocumentProvider()
    private val uri = provider.put("content://docs/note.md", "alpha\n")

    private fun TestScope.fixture() = StoreFixture(tmp.root, provider, UnconfinedTestDispatcher(testScheduler))

    private suspend fun StoreFixture.opened(): OpenOutcome.Opened = store.open(uri) as OpenOutcome.Opened

    /** Dies after an `onPause` flush and launches again: the real scan, then the document reopened. */
    private suspend fun TestScope.dieAndReopen(before: StoreFixture): OpenOutcome.Opened {
        before.store.flushJournals()
        val after = fixture()
        after.store.scanForRecovery()
        return after.opened()
    }

    /** One undo step per call: a word typed, then the run broken, as a cursor move does. */
    private fun OpenDocument.step(text: String) {
        replace(document.text.length, 0, text)
        document.breakTypingRun()
    }

    private val OpenDocument.text: String get() = document.text.toString()

    @Test
    fun `after recovery each Undo takes back one step, not every recovered edit at once`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.step("one ")
            doc.step("two ")
            doc.step("three")

            val back = dieAndReopen(first)

            assertTrue(back.recovery is Recovery.Restored)
            val again = back.document
            assertEquals("alpha\none two three", again.text)
            again.undo()
            assertEquals("alpha\none two ", again.text)
            again.undo()
            assertEquals("alpha\none ", again.text)
            again.redo()
            assertEquals("alpha\none two ", again.text)
            again.undo()
            again.undo()
            assertEquals("alpha\n", again.text)
            assertFalse(again.document.canUndo)
        }

    @Test
    fun `undo and redo before the death come back as they were, redo stack included`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.step("one ")
            doc.step("two")
            doc.undo()

            val again = dieAndReopen(first).document

            assertEquals("alpha\none ", again.text)
            assertTrue(again.document.canRedo)
            again.redo()
            assertEquals("alpha\none two", again.text)
        }

    @Test
    fun `after edit, save and death the document opens clean and Undo still works`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.step("one ")
            doc.step("two")
            assertTrue(first.store.save(uri) is SaveOutcome.Saved)

            val back = dieAndReopen(first)

            assertNull("nothing to recover, so no banner", back.recovery)
            val again = back.document
            assertFalse(again.isDirty)
            assertEquals("alpha\none two", again.text)
            again.undo()
            assertEquals("alpha\none ", again.text)
            assertTrue(again.isDirty)
            again.redo()
            assertEquals("alpha\none two", again.text)
        }

    @Test
    fun `a clean journal is never offered for recovery`() =
        runTest {
            val first = fixture()
            first.opened().document.step("one")
            first.store.save(uri)
            first.store.flushJournals()
            // Review r3 L3: the case under test is a journal on disk that holds history, not no journal.
            assertTrue(first.journals.holdsHistoryOnly(uri.value))

            val after = fixture()
            assertTrue(
                after.store
                    .scanForRecovery()
                    .offers
                    .isEmpty(),
            )
            val back = after.opened()
            assertNull(back.recovery)
            assertTrue(back.document.document.canUndo)
        }

    @Test
    fun `a clean journal after several saves and an oversize step is never offered for recovery`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.step("one ")
            first.store.save(uri)
            doc.step("two")
            first.store.save(uri)
            // Review r3 M2: a step bigger than the 8 MB tail, undone, then a save: nothing on disk is unsaved.
            doc.replace(doc.document.text.length, 0, "p".repeat(4_300_000))
            doc.undo()
            first.store.save(uri)
            first.store.flushJournals()

            assertTrue(
                fixture()
                    .store
                    .scanForRecovery()
                    .offers
                    .isEmpty(),
            )
        }

    @Test
    fun `history survives two deaths in a row`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.step("one ")
            doc.step("two")
            val second = fixture().also { dieAndReopenInto(first, it) }
            val third = fixture().also { dieAndReopenInto(second, it) }
            val again = third.store[uri]!!

            assertEquals("alpha\none two", again.text)
            again.undo()
            assertEquals("alpha\none ", again.text)
            again.undo()
            assertEquals("alpha\n", again.text)
        }

    @Test
    fun `history survives a death after a save and then more typing`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.step("one ")
            first.store.save(uri)
            doc.step("two")

            val again = dieAndReopen(first).document

            assertTrue(again.isDirty)
            again.undo()
            assertEquals("alpha\none ", again.text)
            again.undo()
            assertEquals("alpha\n", again.text)
        }

    @Test
    fun `an undo after a save is journalled, and dies dirty with its redo`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.step("one ")
            doc.step("two")
            first.store.save(uri)
            doc.undo()

            val back = dieAndReopen(first)

            assertTrue(back.recovery is Recovery.Restored)
            assertEquals("alpha\none ", back.document.text)
            back.document.redo()
            assertEquals("alpha\none two", back.document.text)
        }

    @Test
    fun `a compound edit and a paste in chunks come back as one step each`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.step("x ")
            // Replace all: one compound edit of several ops.
            doc.document.beginEditGroup()
            doc.replace(0, 1, "A")
            doc.replace(doc.document.text.length - 1, 1, "X")
            doc.document.endEditGroup()
            // A paste applied in chunks across frames, flushed (as onPause would) in the middle.
            doc.document.beginEditGroup()
            doc.replace(doc.document.text.length, 0, "chunk1 ")
            first.store.flushJournals()
            doc.replace(doc.document.text.length, 0, "chunk2")
            doc.document.endEditGroup()

            val again = dieAndReopen(first).document

            assertEquals("Alpha\nxXchunk1 chunk2", again.text)
            again.undo()
            assertEquals("Alpha\nxX", again.text)
            again.undo()
            assertEquals("alpha\nx ", again.text)
            again.undo()
            assertEquals("alpha\n", again.text)
        }

    @Test
    fun `a change made outside while the app was dead ends the history`() =
        runTest {
            val first = fixture()
            first.opened().document.step("one")
            first.store.save(uri)
            first.store.flushJournals()
            provider.externalWrite(uri, "theirs\n".toByteArray())

            val after = fixture()
            after.store.scanForRecovery()
            val back = after.opened()

            assertNull(back.recovery)
            assertEquals("theirs\n", back.document.text)
            assertFalse(back.document.document.canUndo)
            assertFalse(first.journals.fileFor(uri.value).exists())
        }

    @Test
    fun `a change made outside while open ends the history`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.step("one")
            first.store.save(uri)
            provider.externalWrite(uri, "theirs\n".toByteArray())
            assertEquals(OutsideChange.Reloaded, first.store.checkOutside(uri, CheckReason.Periodic))

            val again = dieAndReopen(first).document

            assertEquals("theirs\n", again.text)
            assertFalse(again.document.canUndo)
        }

    @Test
    fun `closing the document deletes its history`() =
        runTest {
            val first = fixture()
            first.opened().document.step("one")
            first.store.save(uri)
            assertTrue(first.journals.fileFor(uri.value).exists())

            assertEquals(CloseOutcome.Closed, first.store.close(uri))

            assertFalse(first.journals.fileFor(uri.value).exists())
            assertFalse(dieAndReopen(first).document.document.canUndo)
        }

    @Test
    fun `don't save deletes the history with the edits`() =
        runTest {
            val first = fixture()
            first.opened().document.step("one")

            assertEquals(CloseOutcome.Closed, first.store.close(uri, discardUnsaved = true))

            assertFalse(first.journals.fileFor(uri.value).exists())
        }

    @Test
    fun `history of a document the launch did not reopen is deleted once the launch settles`() =
        runTest {
            val first = fixture()
            first.opened().document.step("one")
            first.store.save(uri)
            first.store.flushJournals()
            val other = provider.put("content://docs/other.md", "other\n")

            val after = fixture()
            after.store.scanForRecovery()
            after.store.open(other)
            after.store.releaseUnclaimedHistory()

            assertFalse(after.journals.fileFor(uri.value).exists())
        }

    @Test
    fun `history of the document the launch reopened is kept when the launch settles`() =
        runTest {
            val first = fixture()
            first.opened().document.step("one")
            first.store.save(uri)
            first.store.flushJournals()

            val after = fixture()
            after.store.scanForRecovery()
            val again = after.opened().document
            after.store.releaseUnclaimedHistory()

            assertTrue(after.journals.holdsHistoryOnly(uri.value))
            again.undo()
            assertEquals("alpha\n", again.text)
        }

    @Test
    fun `save as carries the history to the new file`() =
        runTest {
            val first = fixture()
            val doc = first.opened().document
            doc.step("one")
            val target = provider.put("content://docs/copy.md", "")
            assertTrue(first.store.saveAs(uri, target) is SaveOutcome.Saved)
            first.store.flushJournals()

            val after = fixture()
            after.store.scanForRecovery()
            val again = (after.store.open(target) as OpenOutcome.Opened).document

            again.undo()
            assertEquals("alpha\n", again.text)
            assertFalse(after.journals.fileFor(uri.value).exists())
        }

    @Test
    fun `an evicted clean document lets its history go`() =
        runTest {
            val first = fixture()
            first.opened().document.step("one")
            first.store.save(uri)
            first.store.close(uri) // KeptUnsaved is not possible: it is clean, so this closes it…
            assertFalse(first.journals.fileFor(uri.value).exists())
            // …and an entry no window shows, evicted under memory pressure, lets go the same way.
            val doc = first.opened().document
            doc.step("two")
            first.store.save(uri)
            doc.windows = 0
            assertEquals(1, first.store.evictInactiveClean())

            assertFalse(first.journals.fileFor(uri.value).exists())
        }

    private suspend fun dieAndReopenInto(
        before: StoreFixture,
        after: StoreFixture,
    ) {
        before.store.flushJournals()
        after.store.scanForRecovery()
        after.store.open(uri)
    }
}
