package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.InterleavingDispatcher
import com.scorchsoft.ssmarkdown.files.fakes.StoreFixture
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Keystrokes interleaved with every disk wait of every store flow
 * (data-safety review DS-1…DS-8). The [InterleavingDispatcher] types
 * while the store is suspended on I/O, as the main looper does on a
 * phone; then the process "dies" and a fresh store over the same
 * directories recovers. The invariant, for every flow and every hop:
 * what recovery brings back is exactly the live text, and nothing typed
 * is missing from the live text.
 */
class InterleavingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private inner class World(
        val root: File = tmp.newFolder(),
    ) {
        /** True once a hooked keystroke actually ran (a flow may make fewer hops than a test tries). */
        var typed = false
        val provider = FakeDocumentProvider()
        val uri: DocumentUri = provider.put("content://docs/note.md", "alpha\nbeta\n")
        val io = InterleavingDispatcher()
        val fixture = StoreFixture(root, provider, io)
        val store get() = fixture.store

        suspend fun opened(target: DocumentUri = uri): OpenDocument =
            (store.open(target) as OpenOutcome.Opened).document

        /** The process dies after an `onPause` flush; a new store recovers [target]. */
        suspend fun recover(target: DocumentUri = uri): OpenOutcome.Opened {
            store.flushJournals()
            val after = StoreFixture(root, provider, InterleavingDispatcher())
            after.store.scanForRecovery()
            return after.store.open(target) as OpenOutcome.Opened
        }

        suspend fun assertRecoversTo(
            live: String,
            target: DocumentUri = uri,
        ) {
            val back = recover(target)
            assertFalse("recovery kept: ${back.recovery}", back.recovery is Recovery.Kept)
            assertFalse("damaged journal", (back.recovery as? Recovery.Restored)?.damaged == true)
            assertEquals(
                live,
                back.document.document.text
                    .toString(),
            )
        }

        val quarantined: Int get() = File(File(root, "journal"), "quarantine").list()?.size ?: 0
    }

    private fun OpenDocument.type(text: String) {
        replace(document.text.length, 0, text)
    }

    private fun OpenDocument.live() = document.text.toString()

    /**
     * Runs [scenario] once for each of the first [hops] disk hops of the
     * flow it starts: `typeAt(action)` makes that run's keystroke land
     * right after that hop. Call it just before starting the flow.
     */
    private fun everyHop(
        hops: Int,
        scenario: suspend TestScope.(world: World, typeAt: (() -> Unit) -> Unit) -> Unit,
    ) {
        for (hop in 1..hops) {
            runTest {
                val world = World()
                scenario(world) { action ->
                    world.io.afterHop(hop) {
                        action()
                        world.typed = true
                    }
                }
            }
        }
    }

    @Test
    fun `DS-1 DS-2 typing during and after a save is journalled on the right base`() =
        everyHop(hops = 3) { world, typeAt ->
            val doc = world.opened()
            doc.type("one ")
            typeAt { doc.type("during ") }
            assertTrue(world.store.save(world.uri) is SaveOutcome.Saved)
            doc.type("after")

            assertTrue(doc.isDirty)
            assertEquals("alpha\nbeta\none ${if (world.typed) "during " else ""}after", doc.live())
            world.assertRecoversTo(doc.live())
        }

    @Test
    fun `DS-1 typing right after a quiet save starts a fresh journal on the new base`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.type("one")
            assertTrue(world.store.save(world.uri) is SaveOutcome.Saved)
            assertFalse(
                world.fixture.journals
                    .fileFor(world.uri.value)
                    .exists(),
            )

            doc.type(" two")
            assertTrue(
                world.fixture.journals
                    .fileFor(world.uri.value)
                    .exists(),
            )
            assertTrue(doc.journalHealthy)
            world.assertRecoversTo("alpha\nbeta\none two")
        }

    @Test
    fun `DS-5 typing during an autosave never cancels it, and the journal follows the new base`() =
        runTest {
            val world = World()
            val autosave = AutosaveScheduler(backgroundScope, world.store, isEnabled = { true }).also { it.start() }
            val doc = world.opened()
            val finished = mutableListOf<SaveOutcome>()
            world.store.addListener { if (it is DocumentEvent.SaveFinished) finished += it.outcome }
            doc.type("one")
            world.io.afterEveryHop = { doc.type("+") }
            advanceTimeBy(2_001)
            runCurrent()
            world.io.afterEveryHop = {}

            assertTrue("a save finished", finished.isNotEmpty())
            assertTrue(finished.all { it is SaveOutcome.Saved })
            advanceTimeBy(5_000)
            advanceUntilIdle()
            assertEquals(null, autosave.pausedBy(world.uri))
            assertFalse(doc.isDirty)
            assertEquals(doc.live(), world.provider.text(world.uri))

            doc.type(" more")
            world.assertRecoversTo(doc.live())
            autosave.stop()
        }

    @Test
    fun `DS-6 typing during Save as is journalled under the new uri`() =
        everyHop(hops = 3) { world, typeAt ->
            val target = world.provider.put("content://docs/copy.md", "")
            val doc = world.opened()
            doc.type("one ")
            typeAt { doc.type("during ") }
            assertTrue(world.store.saveAs(world.uri, target) is SaveOutcome.Saved)
            doc.type("after")

            assertEquals(target, doc.uri)
            assertFalse(
                world.fixture.journals
                    .fileFor(world.uri.value)
                    .exists(),
            )
            world.assertRecoversTo(doc.live(), target)
        }

    @Test
    fun `DS-4 a keystroke during a silent reload turns it into a conflict and is never thrown away`() =
        everyHop(hops = 3) { world, typeAt ->
            val doc = world.opened()
            world.provider.externalWrite(world.uri, "alpha\nbeta\ngamma\n".toByteArray())
            typeAt { (world.store[world.uri] ?: doc).type("MY TYPING") }
            world.store.checkOutside(world.uri, CheckReason.Periodic)

            val current = world.store[world.uri] ?: error("closed")
            assertEquals(world.typed, current.live().contains("MY TYPING"))
            assertEquals(world.typed, current.isDirty)
            if (world.typed) world.assertRecoversTo(current.live())
        }

    @Test
    fun `DS-7 Use my version keeps every keystroke typed while the disk is re-read`() =
        everyHop(hops = 3) { world, typeAt ->
            val doc = world.opened()
            doc.type("mine ")
            world.provider.externalWrite(world.uri, "theirs\n".toByteArray())
            world.store.checkOutside(world.uri, CheckReason.Periodic)
            assertNotNull(doc.conflict)
            typeAt { (world.store[world.uri] ?: doc).type("TYPED AFTER TAP") }

            assertEquals(ResolveOutcome.Resolved, world.store.useMine(world.uri))

            val current = world.store[world.uri] ?: error("closed")
            val expected = if (world.typed) "mine TYPED AFTER TAP" else "mine "
            assertTrue(current.live(), current.live().endsWith(expected))
            assertTrue(current.isDirty)
            world.assertRecoversTo(current.live())
        }

    @Test
    fun `DS-7 a settled merge refuses to drop typing that came after it was made`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.replace(0, 0, "mine ")
            world.provider.externalWrite(world.uri, "alpha\nbeta\ntheirs\n".toByteArray())
            world.store.checkOutside(world.uri, CheckReason.Periodic)
            val madeFrom = doc.document.version
            val merged = doc.conflict?.merge(doc.document.text)?.cleanText() ?: error("not clean")
            world.io.afterHop(1) { doc.replace(5, 0, "late ") }

            assertEquals(ResolveOutcome.BufferChanged, world.store.useText(world.uri, merged, madeFrom))
            assertNotNull(doc.conflict)
            assertTrue(doc.live().startsWith("mine late "))

            assertEquals(ResolveOutcome.Resolved, world.store.useCleanMerge(world.uri))
            val current = world.store[world.uri] ?: error("closed")
            assertTrue(current.live(), current.live().startsWith("mine late alpha"))
            assertTrue(current.live(), current.live().contains("theirs"))
            world.assertRecoversTo(current.live())
        }

    @Test
    fun `DS-8 resolving a conflict checkpoints the buffer it replaces`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.type("mine")
            world.provider.externalWrite(world.uri, "theirs\n".toByteArray())
            world.store.checkOutside(world.uri, CheckReason.Periodic)

            world.store.useMine(world.uri)

            assertTrue(
                world.fixture.history.entries
                    .toString(),
                "checkpoint-BeforeMerge:alpha\nbeta\nmine" in world.fixture.history.entries,
            )
        }

    @Test
    fun `DS-8 typing while a checkpoint is written is checkpointed too`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.type("mine")
            world.provider.externalWrite(world.uri, "theirs\n".toByteArray())
            world.store.checkOutside(world.uri, CheckReason.Periodic)
            var typed = false
            world.fixture.history.duringCheckpoint = {
                if (!typed) doc.type(" late").also { typed = true }
            }

            world.store.keepTheirs(world.uri)

            assertTrue(
                world.fixture.history.entries
                    .toString(),
                "checkpoint-KeptTheirs:alpha\nbeta\nmine late" in world.fixture.history.entries,
            )
        }

    @Test
    fun `DS-3 edits recovery could not apply survive every close, and are set aside, not deleted, on new edits`() =
        runTest {
            val world = World()
            world.opened().type("lost?")
            world.store.flushJournals()
            world.provider.externalWrite(world.uri, "changed elsewhere\n".toByteArray())
            File(world.root, "base").deleteRecursively() // No base copy to replay onto.

            val second = StoreFixture(world.root, world.provider, InterleavingDispatcher())
            second.store.scanForRecovery()
            val opened = second.store.open(world.uri) as OpenOutcome.Opened
            assertTrue(opened.recovery is Recovery.Kept)
            assertTrue(opened.document.holdsUnappliedEdits)

            assertEquals(CloseOutcome.Closed, second.store.close(world.uri))
            assertTrue(second.journals.fileFor(world.uri.value).exists())

            val third = StoreFixture(world.root, world.provider, InterleavingDispatcher())
            assertEquals(
                listOf(world.uri),
                third.store
                    .scanForRecovery()
                    .offers
                    .map { it.uri },
            )
            val again = (third.store.open(world.uri) as OpenOutcome.Opened).document
            again.type(" new")
            assertFalse(again.holdsUnappliedEdits)
            assertEquals(1, world.quarantined)
            assertTrue(third.store.save(world.uri) is SaveOutcome.Saved)
            assertEquals(1, world.quarantined)
        }

    @Test
    fun `DS-11 a keystroke on a long line journals the keystroke, not the line`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.replace(0, 0, "x".repeat(200_000))
            world.store.flushJournals()
            val afterPaste =
                world.fixture.journals
                    .fileFor(world.uri.value)
                    .length()

            repeat(10) { doc.replace(100_000 + it, 0, "k") }
            world.store.flushJournals()

            val perKey =
                (
                    world.fixture.journals
                        .fileFor(world.uri.value)
                        .length() - afterPaste
                ) / 10
            assertTrue("bytes per keystroke: $perKey", perKey < 64)
            world.assertRecoversTo(doc.live())
        }

    @Test
    fun `a journal that outgrows its text is compacted to one snapshot`() =
        runTest {
            val world = World()
            val doc = world.opened()
            val chunk = 100_000
            doc.replace(0, 0, "a".repeat(chunk))
            repeat(12) { round -> doc.replace(0, chunk, ('b' + round % 2).toString().repeat(chunk)) }
            world.store.flushJournals()

            val journal =
                world.fixture.journals
                    .fileFor(world.uri.value)
                    .length()
            // 2.5 MB of ops without compaction; with it, at most one snapshot plus under 1 MiB of ops.
            assertTrue("journal bytes: $journal", journal < doc.document.text.length + (1L shl 20))
            world.assertRecoversTo(doc.live())
        }

    @Test
    fun `undo and redo are journalled exactly`() =
        runTest {
            val world = World()
            val doc = world.opened()
            doc.type("one")
            doc.replace(0, 5, "ALPHA")
            doc.undo()
            doc.redo()
            doc.undo()

            world.assertRecoversTo(doc.live())
        }

    @Test
    fun `a journal that cannot start turns the document unhealthy and says so`() =
        runTest {
            val world = World()
            File(world.root, "journal").apply { deleteRecursively() }.writeText("not a directory")
            val doc = world.opened()
            val events = mutableListOf<DocumentEvent>()
            world.store.addListener { events += it }

            doc.type("x")

            assertFalse(doc.journalHealthy)
            assertTrue(events.any { it is DocumentEvent.JournalHealthChanged })
            assertEquals("alpha\nbeta\nx", doc.live())
        }
}
