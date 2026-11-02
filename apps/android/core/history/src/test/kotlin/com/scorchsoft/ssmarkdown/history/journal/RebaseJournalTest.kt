package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * A verified save appends a Rebase marker instead of rewriting the
 * journal (FORMAT.md "Saves", ADR 0016; review r3 M1 to M3). The journal
 * then names the saved file as its base everywhere a base is read: replay,
 * the launch scan, markClean and resume.
 */
class RebaseJournalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = "content://doc/rebase"
    private val original = "alpha\n"
    private val saved = "alpha\none two"

    /** Typed "one " and "two" as two steps, then saved: the journal outlives the save. */
    private fun savedJournal(test: TestStore): JournalWriter {
        val writer = test.start(key, fingerprintOf(original))
        writer.append(TextEdit(6, "", "one "))
        writer.endStep()
        writer.append(TextEdit(10, "", "two"))
        assertTrue(writer.rebase(fingerprintOf(saved)) is JournalWriteResult.Written)
        return writer
    }

    private val savedHistory =
        JournalHistory(listOf(listOf(TextEdit(6, "", "one ")), listOf(TextEdit(10, "", "two"))), emptyList())

    @Test
    fun `a journal that outlived a save replays onto the saved file with its history, and holds no edits`() {
        val test = TestStore(folder.root)
        savedJournal(test).close()

        val replay = test.store.replay(key, saved, fingerprintOf(saved)) as ReplayResult.Ok

        assertEquals(saved, replay.text)
        assertEquals(savedHistory, replay.history)
        assertFalse(replay.editsReplayed)
        assertTrue(test.store.holdsHistoryOnly(key))
        val scan = test.store.scan()
        assertTrue(scan.unclean.isEmpty())
        assertTrue(
            scan.historyOnly
                .single()
                .base
                .matches(fingerprintOf(saved)),
        )
    }

    @Test
    fun `edits after the save are offered on the saved base and replay onto it`() {
        val test = TestStore(folder.root)
        val writer = savedJournal(test)
        writer.endStep()
        writer.append(TextEdit(13, "", "!"))
        writer.close()

        val scan = test.store.scan()
        assertTrue(scan.historyOnly.isEmpty())
        assertTrue(
            scan.unclean
                .single()
                .base
                .matches(fingerprintOf(saved)),
        )
        val replay = test.store.replay(key, saved, fingerprintOf(saved)) as ReplayResult.Ok
        assertEquals("$saved!", replay.text)
        assertTrue(replay.editsReplayed)
        assertEquals(3, replay.history.undo.size)
    }

    @Test
    fun `the old base no longer matches, and the mismatch names the saved one`() {
        val test = TestStore(folder.root)
        savedJournal(test).close()

        val replay = test.store.replay(key, original, fingerprintOf(original)) as ReplayResult.BaseMismatch

        assertTrue(replay.journalBase.matches(fingerprintOf(saved)))
    }

    @Test
    fun `markClean and resume judge the journal by the saved base`() {
        val test = TestStore(folder.root)
        savedJournal(test).close()
        val file = test.store.fileFor(key)

        val resumed = test.store.resume(key, fingerprintOf(saved), null, fingerprintOf(saved), savedHistory)
        (resumed as OpenResult.Opened).writer.close()
        assertTrue("replaced in place, nothing set aside", test.store.listSetAside().isEmpty())
        assertTrue(file.exists())
        assertEquals(CleanResult.Deleted, test.store.markClean(key, fingerprintOf(saved)))
    }

    @Test
    fun `a journal that outlived a save is not deleted as if it were on the old base`() {
        val test = TestStore(folder.root)
        savedJournal(test).close()

        assertTrue(test.store.markClean(key, fingerprintOf(original)) is CleanResult.Quarantined)
    }

    @Test
    fun `a torn Rebase leaves the journal on its old base, edits and all`() {
        val test = TestStore(folder.root)
        val writer = test.start(key, fingerprintOf(original))
        writer.append(TextEdit(6, "", "one"))
        writer.flush()
        val file = test.store.fileFor(key)
        val whole = file.length()
        writer.rebase(fingerprintOf("alpha\none"))
        writer.close()
        file.writeBytes(file.readBytes().copyOf((whole + 20).toInt()))

        assertFalse(test.store.holdsHistoryOnly(key))
        val replay = test.store.replay(key, original, fingerprintOf(original)) as ReplayResult.Ok
        assertEquals("alpha\none", replay.text)
    }

    @Test
    fun `salvage after a save starts from the saved file when its copy is there`() {
        val test = TestStore(folder.root)
        val writer = savedJournal(test)
        writer.append(TextEdit(13, "", "!"))
        writer.close()

        val asked = mutableListOf<BaseFingerprint>()
        val salvage =
            test.store.salvage(key) { base ->
                asked += base
                saved.takeIf { base.matches(fingerprintOf(saved)) }
            }

        assertEquals(Salvage.Text("$saved!", damaged = false), salvage)
        assertTrue(asked.single().matches(fingerprintOf(saved)))
    }

    @Test
    fun `a random session with saves replays to the reference text and stacks at every save`() {
        repeat(CASES) { seed ->
            val random = Random(seed)
            var text = Hostile.text(random, maxPieces = 6)
            var base = text
            val test = TestStore(folder.newFolder())
            val writer = test.start(key, fingerprintOf(base))
            val undo = ArrayList<MutableList<TextEdit>>()
            var open = false
            repeat(random.nextInt(1, STEPS)) {
                if (random.nextInt(6) == 0) {
                    writer.rebase(fingerprintOf(text))
                    base = text
                } else {
                    val edit = Hostile.edit(random, text, allowLarge = false)
                    if (open && random.nextBoolean()) {
                        writer.endStep()
                        open = false
                    }
                    writer.append(edit)
                    text = text.applying(edit)
                    if (!open) undo += ArrayList<TextEdit>()
                    undo.last() += edit
                    open = true
                }
            }
            writer.close()

            val replay = test.store.replay(key, base, fingerprintOf(base)) as ReplayResult.Ok
            assertEquals("seed $seed", text, replay.text)
            assertEquals("seed $seed", JournalHistory(undo.map { it.toList() }, emptyList()), replay.history)
            assertEquals(
                "seed $seed",
                replay.editsReplayed,
                test.store
                    .scan()
                    .unclean
                    .isNotEmpty(),
            )
        }
    }

    private companion object {
        const val CASES = 100
        const val STEPS = 40
    }
}
