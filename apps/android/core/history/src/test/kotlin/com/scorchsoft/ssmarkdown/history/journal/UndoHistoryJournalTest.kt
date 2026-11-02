package com.scorchsoft.ssmarkdown.history.journal

import com.scorchsoft.ssmarkdown.history.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Format version 2: the undo history in the journal (FORMAT.md "Undo
 * history", ADR 0016). Replay must rebuild the steps the editor's undo
 * stack had, from what the stack reported, and never anything else.
 */
class UndoHistoryJournalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = "content://doc/undo"

    @Test
    fun `steps, step ends, undo and redo replay to the stacks the editor had`() {
        val base = "hello"
        val test = TestStore(folder.root)
        val writer = test.start(key, fingerprintOf(base))
        val model = Model(base, writer)
        model.edit(TextEdit(5, "", " world"))
        model.edit(TextEdit(11, "", "!"))
        model.seal()
        model.edit(TextEdit(0, "h", "H"))
        model.undo()
        model.undo()
        model.redo()
        writer.close()

        val replay = test.store.replay(key, base, fingerprintOf(base)) as ReplayResult.Ok

        assertEquals(model.text, replay.text)
        assertEquals(model.history(), replay.history)
        assertTrue(replay.editsReplayed)
    }

    @Test
    fun `random sessions replay to the reference stacks at every whole record`() {
        repeat(CASES) { seed ->
            val random = Random(seed)
            val base = Hostile.text(random, maxPieces = 10)
            val test = TestStore(folder.newFolder())
            val writer = test.start(key, fingerprintOf(base))
            val model = Model(base, writer)
            repeat(random.nextInt(1, STEPS)) {
                when (random.nextInt(10)) {
                    0 -> model.undo()
                    1 -> model.redo()
                    2 -> model.seal()
                    3 -> model.compact(test.store, fingerprintOf(base))
                    else -> model.edit(Hostile.edit(random, model.text, allowLarge = false))
                }
            }
            model.writer.close()

            val replay = test.store.replay(key, base, fingerprintOf(base)) as ReplayResult.Ok
            assertEquals("seed $seed", model.text, replay.text)
            assertEquals("seed $seed", model.history(), replay.history)
        }
    }

    @Test
    fun `an undo the file ends inside of is rolled back, text and step together`() {
        val base = "abc"
        val test = TestStore(folder.root)
        val writer = test.start(key, fingerprintOf(base))
        val model = Model(base, writer)
        model.edit(TextEdit(3, "", "1"))
        model.edit(TextEdit(4, "", "2"))
        val before = model.history()
        val textBefore = model.text
        writer.flush()
        val file = test.store.fileFor(key)
        val whole = file.length()
        // The undo marker and the first of its two inverse edits land; the process dies.
        writer.undoStep(2)
        writer.append(TextEdit(4, "2", ""))
        writer.flush()
        writer.close()
        assertTrue(file.length() > whole)

        val replay = test.store.replay(key, base, fingerprintOf(base)) as ReplayResult.Ok

        assertEquals(textBefore, replay.text)
        assertEquals(before, replay.history)
    }

    @Test
    fun `a torn tail inside a step loses only the torn edit`() {
        val base = "abc"
        val test = TestStore(folder.root)
        val writer = test.start(key, fingerprintOf(base))
        val model = Model(base, writer)
        model.edit(TextEdit(3, "", "1"))
        writer.flush()
        val file = test.store.fileFor(key)
        val whole = file.length()
        writer.append(TextEdit(4, "", "2"))
        writer.close()
        file.writeBytes(file.readBytes().copyOf((whole + 5).toInt()))

        val replay = test.store.replay(key, base, fingerprintOf(base)) as ReplayResult.Ok

        assertEquals("abc1", replay.text)
        assertEquals(JournalHistory(listOf(listOf(TextEdit(3, "", "1"))), emptyList()), replay.history)
    }

    @Test
    fun `a clean journal holds history only, replays to its base and is not offered for recovery`() {
        val base = "saved"
        val history = JournalHistory(listOf(listOf(TextEdit(0, "", "saved"))), emptyList())
        val test = TestStore(folder.root)
        val opened = test.store.resume(key, fingerprintOf(base), null, null, history) as OpenResult.Opened
        opened.writer.close()

        val replay = test.store.replay(key, base, fingerprintOf(base)) as ReplayResult.Ok
        assertEquals(base, replay.text)
        assertEquals(history, replay.history)
        assertFalse(replay.editsReplayed)
        assertTrue(test.store.holdsHistoryOnly(key))

        val scan = test.store.scan()
        assertEquals(listOf(key), scan.historyOnly.map { it.documentKey })
        assertTrue(scan.unclean.isEmpty())
    }

    @Test
    fun `a clean journal stops being clean at its first edit`() {
        val base = "saved"
        val test = TestStore(folder.root)
        val history = JournalHistory(listOf(listOf(TextEdit(0, "", "saved"))), emptyList())
        val writer = (test.store.resume(key, fingerprintOf(base), null, null, history) as OpenResult.Opened).writer
        writer.append(TextEdit(5, "", "!"))
        writer.close()

        assertFalse(test.store.holdsHistoryOnly(key))
        assertEquals(
            listOf(key),
            test.store
                .scan()
                .unclean
                .map { it.documentKey },
        )
    }

    @Test
    fun `an empty journal is still an ordinary journal`() {
        // A run killed before its first edit landed: edits began, so a changed file opens as a conflict.
        val test = TestStore(folder.root)
        test.start(key, fingerprintOf("x")).close()

        assertFalse(test.store.holdsHistoryOnly(key))
    }

    @Test
    fun `a clean journal whose history trims to nothing is never offered for recovery`() {
        // Review r3 M2: one step bigger than the tail cap (a paste of 4.3 M chars) leaves no history to write.
        val paste = "p".repeat((JournalHistory.TAIL_MAX_BYTES / 2).toInt())
        val history = JournalHistory(listOf(listOf(TextEdit(0, "", paste))), emptyList())
        val test = TestStore(folder.root)
        (test.store.resume(key, fingerprintOf(paste), null, null, history) as OpenResult.Opened).writer.close()

        // It still says "clean" with an empty History record, never a bare header (which means edits began).
        assertTrue(test.store.holdsHistoryOnly(key))
        assertTrue(
            test.store
                .scan()
                .unclean
                .isEmpty(),
        )
        val replay = test.store.replay(key, paste, fingerprintOf(paste)) as ReplayResult.Ok
        assertFalse(replay.editsReplayed)
    }

    @Test
    fun `a resumed journal carries text and history through a second death`() {
        val history =
            JournalHistory(
                undo = listOf(listOf(TextEdit(0, "", "a"), TextEdit(1, "", "b"))),
                redo = listOf(listOf(TextEdit(2, "", "c"))),
                newestOpen = true,
            )
        val test = TestStore(folder.root)
        val writer = (test.store.resume(key, fingerprintOf(""), "ab", null, history) as OpenResult.Opened).writer
        // The step was still open: this edit joins it.
        writer.append(TextEdit(2, "", "d"))
        writer.close()

        val replay = test.store.replay(key, "", fingerprintOf("")) as ReplayResult.Ok

        assertEquals("abd", replay.text)
        assertEquals(
            listOf(listOf(TextEdit(0, "", "a"), TextEdit(1, "", "b"), TextEdit(2, "", "d"))),
            replay.history.undo,
        )
        assertTrue("an edit clears redo", replay.history.redo.isEmpty())
    }

    @Test
    fun `the history kept is the newest steps that fit the tail`() {
        val big = "x".repeat(1_000)
        val steps = (0 until 10).map { listOf(TextEdit(it * 1_000, "", big)) }
        val history = JournalHistory(steps, emptyList())
        val cap = 3 * JournalHistory.tailBytes(steps[0][0])

        val tail = history.trimmedTo(cap)

        assertEquals(steps.takeLast(3), tail.undo)
    }

    @Test
    fun `a damaged history record is corruption, never a wrong history`() {
        val test = TestStore(folder.root)
        val history = JournalHistory(listOf(listOf(TextEdit(0, "", "abc"))), emptyList())
        (test.store.resume(key, fingerprintOf("abc"), null, null, history) as OpenResult.Opened).writer.close()
        val file = test.store.fileFor(key)
        val bytes = file.readBytes()
        bytes[bytes.size - 2] = (bytes[bytes.size - 2] + 1).toByte()
        file.writeBytes(bytes)

        val replay = test.store.replay(key, "abc", fingerprintOf("abc"))

        assertTrue("$replay", replay is ReplayResult.Corrupt)
        assertTrue((replay as ReplayResult.Corrupt).history.isEmpty)
    }

    @Test
    fun `a version 1 journal from the last release still replays, with no history`() {
        val fixture = File(folder.root, "v1.ssmj")
        fixture.writeBytes(requireNotNull(javaClass.getResource("/journal/v1-notes.ssmj")).readBytes())
        val base = "# Notes\n\nfirst line\n"

        val replay = JournalReader(fixture).replay(base, fingerprintOf(base)) as ReplayResult.Ok

        assertEquals("Journal\n\nfirst line\ntyped 😀\n", replay.text)
        assertEquals(4L, replay.lastSeq)
        assertTrue(replay.history.isEmpty)
        assertTrue(replay.editsReplayed)
        assertEquals(1, (JournalReader(fixture).header() as HeaderRead.Ok).header.formatVersion)
    }

    @Test
    fun `this build writes version 2`() {
        val test = TestStore(folder.root)
        test.start(key, fingerprintOf("")).close()

        assertEquals(2, (JournalReader(test.store.fileFor(key)).header() as HeaderRead.Ok).header.formatVersion)
    }

    /**
     * A reference undo stack driven the way the editor drives the journal:
     * each call writes what the editor's stack would report and keeps the
     * stacks the editor would hold.
     */
    private class Model(
        var text: String,
        var writer: JournalWriter,
    ) {
        private val undo = ArrayList<MutableList<TextEdit>>()
        private val redo = ArrayList<List<TextEdit>>()
        private var open = false

        fun edit(edit: TextEdit) {
            writer.append(edit)
            text = text.applying(edit)
            redo.clear()
            if (!open) undo += ArrayList<TextEdit>()
            undo.last() += edit
            open = true
        }

        fun seal() {
            if (!open) return
            writer.endStep()
            open = false
        }

        fun undo() {
            val step = undo.removeLastOrNull() ?: return
            writer.undoStep(step.size)
            step.asReversed().forEach { edit ->
                val inverse = edit.inverted()
                writer.append(inverse)
                text = text.applying(inverse)
            }
            redo += step
            open = false
        }

        fun redo() {
            val step = redo.removeLastOrNull() ?: return
            writer.redoStep(step.size)
            step.forEach { edit ->
                writer.append(edit)
                text = text.applying(edit)
            }
            undo += step.toMutableList()
            open = false
        }

        /** What compaction does: a snapshot and the history, in one atomic replace. */
        fun compact(
            store: JournalStore,
            base: BaseFingerprint,
        ) {
            writer.close()
            val history = JournalHistory(undo.map { it.toList() }, redo.toList(), newestOpen = open)
            writer = (store.resume("content://doc/undo", base, text, base, history) as OpenResult.Opened).writer
        }

        fun history() = JournalHistory(undo.map { it.toList() }, redo.toList())
    }

    private companion object {
        const val CASES = 200
        const val STEPS = 50
    }
}
