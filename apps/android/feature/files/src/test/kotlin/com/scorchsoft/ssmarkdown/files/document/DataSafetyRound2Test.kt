package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.InterleavingDispatcher
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * The second data-safety review's probes, kept as regressions: no path
 * destroys or strands text while the UI says it is kept (R2-DS-1…DS-6).
 */
class DataSafetyRound2Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private inner class World(
        io: CoroutineDispatcher = InterleavingDispatcher(),
    ) {
        val root: File = tmp.newFolder()
        val provider = FakeDocumentProvider()
        val uri: DocumentUri = provider.put("content://docs/note.md", "alpha\n")
        val fixture = StoreFixture(root, provider, io)
        val store get() = fixture.store
        val history get() = fixture.history

        suspend fun opened(target: DocumentUri = uri): OpenDocument =
            (store.open(target) as OpenOutcome.Opened).document

        /** A new, empty draft the app would have created for a copy. */
        fun draft(id: String = "copy"): DocumentUri = provider.put("${DocumentUri.DRAFT_SCHEME}:$id", "")

        /** Journal files waiting to be recovered (quarantine excluded). */
        val journals: Int get() = File(root, "journal").listFiles { f -> f.isFile }?.size ?: 0
        val quarantined: Int get() = File(File(root, "journal"), "quarantine").list()?.size ?: 0

        /** The process dies after an `onPause` flush; a new store over the same folders. */
        fun restart(): StoreFixture {
            store.flushJournals()
            return StoreFixture(root, provider, InterleavingDispatcher())
        }
    }

    private fun OpenDocument.type(text: String) {
        replace(document.text.length, 0, text)
    }

    private fun OpenDocument.live() = document.text.toString()

    private suspend fun World.conflicted(): OpenDocument {
        val doc = opened()
        doc.type("precious")
        provider.externalWrite(uri, "theirs\n".toByteArray())
        assertEquals(OutsideChange.Conflicted, store.checkOutside(uri, CheckReason.Periodic))
        return doc
    }

    // ---- R2-DS-1: the checkpoint's result decides, and is passed up. ----

    @Test
    fun `R2-DS-1 PROBE-1 keep the file's version refuses when History could not keep a copy`() =
        runTest {
            val world = World()
            val doc = world.conflicted()
            world.history.checkpointResult = CheckpointResult.Failed

            val outcome = world.store.keepTheirs(doc.uri)

            assertEquals(ResolveOutcome.NotKept(CheckpointResult.Failed), outcome)
            assertEquals("alpha\nprecious", doc.live())
            assertNotNull("the conflict is still pending", doc.conflict)
            assertEquals("the journal is still there", 1, world.journals)
        }

    @Test
    fun `R2-DS-1 keep the file's version goes ahead when the user accepts no copy, and says what happened`() =
        runTest {
            val world = World()
            val doc = world.conflicted()
            world.history.checkpointResult = CheckpointResult.Failed

            val outcome = world.store.keepTheirs(doc.uri, evenIfNotKept = true)

            assertEquals(ResolveOutcome.Resolved(CheckpointResult.Failed), outcome)
            assertEquals("theirs\n", doc.live())
        }

    @Test
    fun `R2-DS-1 a recorded checkpoint resolves and says Recorded`() =
        runTest {
            val world = World()
            val doc = world.conflicted()

            assertEquals(ResolveOutcome.Resolved(CheckpointResult.Recorded), world.store.keepTheirs(doc.uri))
        }

    @Test
    fun `R2-DS-1 History off is not a copy either`() =
        runTest {
            val world = World()
            val doc = world.conflicted()
            world.history.checkpointResult = CheckpointResult.Off

            assertEquals(ResolveOutcome.NotKept(CheckpointResult.Off), world.store.keepTheirs(doc.uri))
            assertEquals("alpha\nprecious", doc.live())
        }

    @Test
    fun `R2-DS-1 PROBE-2 discard edits refuses when History could not keep a copy`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.type("precious")
            world.history.checkpointResult = CheckpointResult.Failed

            assertEquals(ResolveOutcome.NotKept(CheckpointResult.Failed), world.store.discardEdits(doc.uri))
            assertEquals("alpha\nprecious", doc.live())
            assertTrue(doc.isDirty)
            assertEquals(1, world.journals)

            assertEquals(
                ResolveOutcome.Resolved(CheckpointResult.Failed),
                world.store.discardEdits(doc.uri, evenIfNotKept = true),
            )
            assertEquals("alpha\n", doc.live())
        }

    @Test
    fun `R2-DS-1 discard edits on a conflict checkpoints once and passes the result up`() =
        runTest {
            val world = World()
            val doc = world.conflicted()
            world.history.checkpointResult = CheckpointResult.Failed

            assertEquals(ResolveOutcome.NotKept(CheckpointResult.Failed), world.store.discardEdits(doc.uri))
            assertEquals(1, world.history.entries.count { it.startsWith("checkpoint") })
            assertEquals("alpha\nprecious", doc.live())
        }

    @Test
    fun `R2-DS-1 a hand-settled merge refuses without a copy, Use mine does not need one`() =
        runTest {
            val world = World()
            val doc = world.conflicted()
            world.history.checkpointResult = CheckpointResult.Failed

            val merged = world.store.useText(doc.uri, "theirs\n", doc.document.version)
            assertEquals(ResolveOutcome.NotKept(CheckpointResult.Failed), merged)
            assertEquals("alpha\nprecious", doc.live())

            // "Use my version" keeps every keystroke of mine, so nothing of mine needs History.
            assertEquals(ResolveOutcome.Resolved(CheckpointResult.Failed), world.store.useMine(doc.uri))
            assertEquals("alpha\nprecious", doc.live())
        }

    @Test
    fun `R2-DS-1 PROBE-5 Don't save refuses to close when History could not keep a copy`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.type("precious")
            world.store.flushJournals()
            world.history.checkpointResult = CheckpointResult.Failed

            assertEquals(
                CloseOutcome.NotKept(CheckpointResult.Failed),
                world.store.close(doc.uri, discardUnsaved = true),
            )
            assertSame(doc, world.store[doc.uri])
            assertEquals(1, world.journals)

            // Still shown in its window: the user may ask again, and accept no copy.
            assertEquals(
                CloseOutcome.Closed,
                world.store.close(doc.uri, discardUnsaved = true, evenIfNotKept = true),
            )
            assertNull(world.store[doc.uri])
            assertEquals(0, world.journals)
        }

    // ---- R2-DS-5: the last keystrokes are never in no checkpoint. ----

    @Test
    fun `R2-DS-5 PROBE-4 typing through every checkpoint attempt asks again instead of dropping it`() =
        runTest {
            val world = World()
            val doc = world.conflicted()
            var n = 0
            world.history.duringCheckpoint = { doc.type(" k${n++}") }

            val outcome = world.store.keepTheirs(doc.uri)

            assertEquals(ResolveOutcome.BufferChanged, outcome)
            assertTrue(doc.live(), doc.live().endsWith(" k${n - 1}"))
            assertNotNull(doc.conflict)
        }

    @Test
    fun `R2-DS-5 a keystroke during Don't save's checkpoint is in the next one`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.type("x")
            var typed = false
            world.history.duringCheckpoint = {
                if (!typed) doc.type("y")
                typed = true
            }

            assertEquals(CloseOutcome.Closed, world.store.close(doc.uri, discardUnsaved = true))
            assertEquals("checkpoint-ClosedWithoutSaving:alpha\nxy", world.history.entries.last())
        }

    // ---- R2-DS-2: a file that cannot be reached after a restart. ----

    @Test
    fun `R2-DS-2 PROBE-3 edits to a file whose grant is gone come back as a copy`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.type("precious")
            world.provider.revoked += world.uri
            val after = world.restart()
            assertEquals(
                1,
                after.store
                    .scanForRecovery()
                    .offers.size,
            )
            val failed = after.store.open(world.uri)
            assertTrue(failed.toString(), failed is OpenOutcome.Failed && failed.hasUnsavedEdits)

            val copy = after.store.recoverAsCopy(world.uri, world.draft())

            assertTrue(copy.toString(), copy is CopyOutcome.Copied)
            copy as CopyOutcome.Copied
            assertTrue(copy.complete)
            assertEquals("alpha\nprecious", copy.document.live())
            assertEquals("the copy is on disk", "alpha\nprecious", world.provider.text(copy.document.uri))
            assertFalse(copy.document.isDirty)
            // Set aside, never deleted, and no longer offered at every launch.
            assertEquals(0, world.journals)
            assertEquals(1, world.quarantined)
            assertTrue(
                StoreFixture(
                    world.root,
                    world.provider,
                    InterleavingDispatcher(),
                ).store.scanForRecovery().offers.isEmpty(),
            )
        }

    @Test
    fun `R2-DS-2 a moved file's edits come back as a copy too`() =
        runTest {
            val world = World()
            world.opened().type("precious")
            world.provider.delete(world.uri)
            val after = world.restart()
            after.store.scanForRecovery()

            val copy = after.store.recoverAsCopy(world.uri, world.draft()) as CopyOutcome.Copied

            assertEquals("alpha\nprecious", copy.document.live())
        }

    @Test
    fun `R2-DS-2 a copy is refused for a document that is open and running its own journal`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.type("mine")

            assertTrue(world.store.recoverAsCopy(doc.uri, world.draft()) is CopyOutcome.Unavailable)
            assertEquals(1, world.journals)
        }

    // ---- R2-DS-3: kept edits can be seen. ----

    @Test
    fun `R2-DS-3 kept edits with no base copy open as a copy, and the file keeps its own text`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.type("precious")
            val after = world.restart()
            after.store.scanForRecovery()
            File(world.root, "base").deleteRecursively() // The copy they were made on is gone.
            world.provider.externalWrite(world.uri, "theirs\n".toByteArray())
            val reopened = after.store.open(world.uri) as OpenOutcome.Opened
            assertTrue(reopened.recovery is Recovery.Kept)

            val copy = after.store.recoverAsCopy(world.uri, world.draft()) as CopyOutcome.Copied

            // No snapshot and no base: only what was typed can be read, and the copy says so.
            assertFalse(copy.complete)
            assertEquals("precious", copy.document.live())
            assertEquals("theirs\n", reopened.document.live())
            assertFalse("the held journal is set aside", reopened.document.holdsUnappliedEdits)
            assertEquals(1, world.quarantined)
        }

    @Test
    fun `R2-DS-3 set-aside journals are listed and open as a copy`() =
        runTest {
            val world = World()
            world.opened().type("precious")
            val after = world.restart()
            after.store.scanForRecovery()
            File(world.root, "base").deleteRecursively()
            world.provider.externalWrite(world.uri, "theirs\n".toByteArray())
            val reopened = (after.store.open(world.uri) as OpenOutcome.Opened).document
            reopened.type("new run") // Editing moves the held journal aside.

            val listed = after.store.setAsideEdits()

            assertEquals(1, listed.size)
            assertEquals(world.uri, listed.single().uri)
            val copy = after.store.recoverSetAsideAsCopy(listed.single(), world.draft()) as CopyOutcome.Copied
            assertEquals("precious", copy.document.live())
        }

    // ---- R2-DS-4: Save as never orphans an open document. ----

    @Test
    fun `R2-DS-4 PROBE-6 Save as onto a file open with unsaved edits is refused`() =
        runTest {
            val world = World()
            val a = world.provider.put("content://docs/a.md", "A\n")
            val b = world.provider.put("content://docs/b.md", "B\n")
            val docA = world.opened(a)
            docA.type("unsaved in A")
            val docB = world.opened(b)
            docB.type("more B")

            val outcome = world.store.saveAs(b, a)

            assertEquals(SaveOutcome.TargetOpen, outcome)
            assertSame(docA, world.store[a])
            assertSame(docB, world.store[b])
            assertTrue(world.store.dirtyDocuments.any { it === docA })
            assertEquals("A\n", world.provider.text(a))
        }

    @Test
    fun `R2-DS-4 Save as onto a file open in a window is refused even when it is clean`() =
        runTest {
            val world = World()
            val a = world.provider.put("content://docs/a.md", "A\n")
            val b = world.provider.put("content://docs/b.md", "B\n")
            val docA = world.opened(a)
            world.opened(b).type("more B")

            assertEquals(SaveOutcome.TargetOpen, world.store.saveAs(b, a))
            assertSame(docA, world.store[a])
        }

    @Test
    fun `R2-DS-4 Save as onto a clean entry no window shows replaces it`() =
        runTest {
            val world = World()
            val a = world.provider.put("content://docs/a.md", "A\n")
            val b = world.provider.put("content://docs/b.md", "B\n")
            val docA = world.opened(a)
            docA.type("x")
            assertEquals(CloseOutcome.KeptUnsaved, world.store.close(a))
            world.store.save(a) // Save all: clean now, and no window shows it.
            val docB = world.opened(b)
            docB.type("more B")

            assertTrue(world.store.saveAs(b, a) is SaveOutcome.Saved)
            assertSame(docB, world.store[a])
        }

    // ---- R2-DS-6: close and eviction wait for the entry's lock. ----

    /** Runs I/O inline, or holds it until [release] while [gated]. */
    private class GateDispatcher : CoroutineDispatcher() {
        var gated = false
        private val held = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (gated) held += block else block.run()
        }

        fun release() {
            gated = false
            while (held.isNotEmpty()) held.removeFirst().run()
        }
    }

    @Test
    fun `R2-DS-6 Don't save during a save waits for it, and leaves no journal behind`() =
        runTest {
            val gate = GateDispatcher()
            val world = World(gate)
            val doc = world.opened()
            doc.type("x")
            gate.gated = true
            val save = async { world.store.save(doc.uri) }
            runCurrent() // The save holds the lock, suspended on its write.
            doc.type("y") // Typed during the save: it resumes a journal when it lands.
            val close = async { world.store.close(doc.uri, discardUnsaved = true) }
            runCurrent()
            gate.release()
            runCurrent()

            assertTrue(save.await() is SaveOutcome.Saved)
            assertEquals(CloseOutcome.Closed, close.await())
            assertNull(world.store[doc.uri])
            assertEquals("no journal for a document the user chose not to keep", 0, world.journals)
        }

    @Test
    fun `R2-DS-6 eviction skips an entry whose lock is held`() =
        runTest {
            val gate = GateDispatcher()
            val world = World(gate)
            val doc = world.opened()
            doc.type("x")
            assertEquals(CloseOutcome.KeptUnsaved, world.store.close(doc.uri))
            world.store.save(doc.uri)
            gate.gated = true
            val check = async { world.store.checkOutside(doc.uri, CheckReason.Periodic) }
            runCurrent() // The check holds the lock, suspended on the disk.

            val evicted = async { world.store.evictInactiveClean() }
            runCurrent()
            gate.release()
            runCurrent()

            assertEquals(0, evicted.await())
            assertSame(doc, world.store[doc.uri])
            check.await()
        }
}
