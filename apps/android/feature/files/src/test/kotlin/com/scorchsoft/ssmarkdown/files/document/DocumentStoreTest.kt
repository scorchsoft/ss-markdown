package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.FakeMemorySource
import com.scorchsoft.ssmarkdown.files.fakes.MB
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.files.memory.Budget
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = FakeDocumentProvider()
    private val uri = provider.put("content://docs/note.md", "# Note\n\nfirst line\n")

    private fun TestScope.fixture(memory: FakeMemorySource = FakeMemorySource()) =
        StoreFixture(tmp.root, provider, UnconfinedTestDispatcher(testScheduler), memory)

    private suspend fun DocumentStore.opened(target: DocumentUri = uri): OpenDocument =
        (open(target) as OpenOutcome.Opened).document

    @Test
    fun `one entry per uri, shared by every window`() =
        runTest {
            val store = fixture().store
            val first = store.opened()
            val second = store.opened()

            assertSame(first, second)
            assertEquals(CloseOutcome.StillShown, store.close(uri))
            assertEquals(CloseOutcome.Closed, store.close(uri))
            assertNull(store[uri])
        }

    @Test
    fun `an edit is dirty and journalled, and a save makes it clean and ends the journal`() =
        runTest {
            val fixture = fixture()
            val doc = fixture.store.opened()
            doc.replace(doc.document.text.length, 0, "second line\n")

            assertTrue(doc.isDirty)
            assertTrue(fixture.journals.fileFor(uri.value).exists())

            val outcome = fixture.store.save(uri)

            assertTrue(outcome is SaveOutcome.Saved)
            assertFalse(doc.isDirty)
            assertEquals("# Note\n\nfirst line\nsecond line\n", provider.text(uri))
            assertFalse(fixture.journals.fileFor(uri.value).exists())
            assertEquals(
                listOf("opened:# Note\n\nfirst line\n", "saved:# Note\n\nfirst line\nsecond line\n"),
                fixture.history.entries,
            )
        }

    @Test
    fun `a dirty document is kept when its window closes`() =
        runTest {
            val store = fixture().store
            store.opened().replace(0, 0, "x")

            assertEquals(CloseOutcome.KeptUnsaved, store.close(uri))
            assertTrue(store[uri]?.isDirty == true)
        }

    @Test
    fun `unsaved edits come back after a crash`() =
        runTest {
            val before = fixture()
            val doc = before.store.opened()
            doc.replace(0, 0, "typed ")
            doc.undo()
            doc.redo()
            before.store.flushJournals() // onPause; then the process dies without closing anything.

            val after = fixture()
            val scan = after.store.scanForRecovery()
            assertEquals(listOf(uri), scan.offers.map { it.uri })

            val outcome = after.store.open(uri) as OpenOutcome.Opened
            assertTrue(outcome.recovery is Recovery.Restored)
            assertEquals(
                "typed # Note\n\nfirst line\n",
                outcome.document.document.text
                    .toString(),
            )
            assertTrue(outcome.document.isDirty)
            assertEquals("# Note\n\nfirst line\n", provider.text(uri)) // The disk was not touched.
        }

    @Test
    fun `recovery onto a file changed meanwhile becomes a conflict with all three texts`() =
        runTest {
            val before = fixture()
            before.store.opened().replace(0, 0, "mine ")
            before.store.flushJournals()
            provider.externalWrite(uri, "# Note\n\nfirst line\ntheirs\n".toByteArray())

            val after = fixture()
            after.store.scanForRecovery()
            val outcome = after.store.open(uri) as OpenOutcome.Opened
            val doc = outcome.document

            assertTrue(outcome.recovery is Recovery.Conflicted)
            assertEquals("mine # Note\n\nfirst line\n", doc.document.text.toString())
            val conflict = doc.conflict ?: error("no conflict")
            assertEquals("# Note\n\nfirst line\n", conflict.base)
            assertEquals("# Note\n\nfirst line\ntheirs\n", conflict.theirs)
            val merged = conflict.merge(doc.document.text)?.cleanText() ?: error("not clean")
            assertEquals("mine # Note\n\nfirst line\ntheirs\n", merged)

            assertTrue(after.store.save(uri) is SaveOutcome.Conflict)
            assertEquals("# Note\n\nfirst line\ntheirs\n", provider.text(uri))

            assertEquals(
                ResolveOutcome.Resolved(CheckpointResult.Recorded),
                after.store.useText(uri, merged, doc.document.version),
            )
            assertTrue(after.store.save(uri) is SaveOutcome.Saved)
            assertEquals(merged, provider.text(uri))
        }

    @Test
    fun `a clean buffer reloads silently, with a mapped cursor and no old undo`() =
        runTest {
            val fixture = fixture()
            val doc = fixture.store.opened()
            var reloaded: DocumentEvent.Reloaded? = null
            fixture.store.addListener { if (it is DocumentEvent.Reloaded) reloaded = it }
            provider.externalWrite(uri, "inserted\n# Note\n\nfirst line\n".toByteArray())

            assertEquals(OutsideChange.Reloaded, fixture.store.checkOutside(uri, CheckReason.Resume))

            assertEquals("inserted\n# Note\n\nfirst line\n", doc.document.text.toString())
            assertFalse(doc.document.canUndo)
            assertEquals("inserted\n".length + 2, reloaded?.mapOffset?.invoke(2))
            assertTrue(
                fixture.history.entries
                    .last()
                    .startsWith("outside:inserted"),
            )
        }

    @Test
    fun `a dirty buffer is never touched by an outside change`() =
        runTest {
            val fixture = fixture()
            val doc = fixture.store.opened()
            doc.replace(0, 0, "mine ")
            provider.externalWrite(uri, "theirs\n".toByteArray())

            assertEquals(OutsideChange.Conflicted, fixture.store.checkOutside(uri, CheckReason.Periodic))
            assertEquals("mine # Note\n\nfirst line\n", doc.document.text.toString())

            assertEquals(ResolveOutcome.Resolved(CheckpointResult.Recorded), fixture.store.keepTheirs(uri))
            assertEquals("theirs\n", doc.document.text.toString())
            assertFalse(doc.isDirty)
            assertTrue(fixture.history.entries.any { it == "checkpoint-KeptTheirs:mine # Note\n\nfirst line\n" })
        }

    @Test
    fun `a same-size change in the same whole second is caught by the periodic check`() =
        runTest {
            provider.fixedLastModified = 1_700_000_000_000 // A coarse provider: whole seconds only.
            val fixture = fixture()
            val doc = fixture.store.opened()
            provider.externalWrite(uri, "# Note\n\nFIRST line\n".toByteArray())

            assertEquals(OutsideChange.Reloaded, fixture.store.checkOutside(uri, CheckReason.Periodic))
            assertEquals("# Note\n\nFIRST line\n", doc.document.text.toString())
        }

    @Test
    fun `invalid UTF-8 opens read-only and can never be saved over`() =
        runTest {
            val bad = DocumentUri("content://docs/bad.md")
            provider.put(bad, byteArrayOf('o'.code.toByte(), 0xC3.toByte(), 'k'.code.toByte()))
            val store = fixture().store
            val doc = store.opened(bad)

            assertEquals(ReadOnlyReason.NotUtf8(1), doc.readOnly)
            assertNull(doc.replace(0, 0, "x"))
            assertEquals(SaveOutcome.ReadOnly, store.save(bad))
        }

    @Test
    fun `NUL bytes open read-only as not text`() =
        runTest {
            val binary = DocumentUri("content://docs/blob.md")
            provider.put(binary, byteArrayOf(1, 0, 2))

            assertEquals(ReadOnlyReason.NotText, fixture().store.opened(binary).readOnly)
        }

    @Test
    fun `a file too big for the heap is declined with the numbers`() =
        runTest {
            val small = FakeMemorySource(maxHeap = 32 * MB, usedHeap = 30 * MB, memoryClassMegabytes = 32)
            val outcome = fixture(small).store.open(uri)

            assertTrue(outcome is OpenOutcome.TooLarge)
            assertEquals(32 * MB, (outcome as OpenOutcome.TooLarge).admission.allowedBytes)
        }

    @Test
    fun `a low-RAM phone opens in the lean budget`() =
        runTest {
            val doc = fixture(FakeMemorySource(isLowRamDevice = true)).store.opened()

            assertEquals(Budget.Lean, doc.budget)
        }

    @Test
    fun `a deleted file keeps its text and Save as moves it`() =
        runTest {
            val fixture = fixture()
            val doc = fixture.store.opened()
            doc.replace(0, 0, "keep ")
            provider.delete(uri)

            assertEquals(OutsideChange.Missing, fixture.store.checkOutside(uri, CheckReason.Resume))
            assertTrue(fixture.store.save(uri) is SaveOutcome.Missing)
            assertEquals("keep # Note\n\nfirst line\n", doc.document.text.toString())

            val target = provider.put("content://docs/new.md", "")
            assertTrue(fixture.store.saveAs(uri, target) is SaveOutcome.Saved)
            assertEquals("keep # Note\n\nfirst line\n", provider.text(target))
            assertSame(doc, fixture.store[target])
            assertFalse(doc.isDirty)
            assertFalse(fixture.journals.fileFor(uri.value).exists())
        }

    @Test
    fun `Save as takes the target's name, so a draft never shows its file id`() =
        runTest {
            val fixture = fixture()
            val doc = fixture.store.opened()
            doc.replace(0, 0, "named ")

            val target = provider.put("content://docs/Release notes.md", "")
            assertTrue(fixture.store.saveAs(uri, target) is SaveOutcome.Saved)

            assertEquals("Release notes.md", doc.displayName)
        }

    @Test
    fun `a Save as that fails on the target leaves the source neither missing nor in conflict`() =
        runTest {
            val fixture = fixture()
            val doc = fixture.store.opened()
            doc.replace(0, 0, "mine ")
            val events = ArrayList<DocumentEvent>()
            fixture.store.addListener { events += it }

            val gone = DocumentUri("content://docs/gone.md")
            assertTrue(fixture.store.saveAs(uri, gone) is SaveOutcome.Missing)

            assertFalse(doc.fileMissing)
            assertNull(doc.conflict)
            assertTrue(events.none { it is DocumentEvent.FileMissing || it is DocumentEvent.ConflictDetected })
            assertSame(doc, fixture.store[uri])
            assertTrue(doc.isDirty)
            assertEquals("mine # Note\n\nfirst line\n", doc.document.text.toString())
        }

    @Test
    fun `discarding recovered edits restores the disk text and keeps them in history`() =
        runTest {
            val before = fixture()
            before.store.opened().replace(0, 0, "oops ")
            before.store.flushJournals()

            val after = fixture()
            after.store.scanForRecovery()
            val doc = after.store.opened()

            assertEquals(ResolveOutcome.Resolved(CheckpointResult.Recorded), after.store.discardEdits(uri))
            assertEquals("# Note\n\nfirst line\n", doc.document.text.toString())
            assertFalse(doc.isDirty)
            assertTrue(after.history.entries.contains("checkpoint-DiscardedEdits:oops # Note\n\nfirst line\n"))
            assertFalse(after.journals.fileFor(uri.value).exists())
        }

    @Test
    fun `a revoked grant keeps the work dirty and journalled`() =
        runTest {
            val fixture = fixture()
            val doc = fixture.store.opened()
            doc.replace(0, 0, "safe ")
            provider.revoked += uri

            assertTrue(fixture.store.save(uri) is SaveOutcome.PermissionLost)
            assertTrue(doc.isDirty)
            assertTrue(fixture.journals.fileFor(uri.value).exists())
        }
}
