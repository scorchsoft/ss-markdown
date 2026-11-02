package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The undo stack's side of undo surviving a process death (ADR 0016): it
 * reports its own grouping decisions in order with the ops, and its
 * stacks round-trip as data.
 */
class UndoHistoryTest {
    private var now = 0L
    private val stack = UndoStack(maxBytes = 1_000_000, typingRunMillis = 1_000, clock = { now })
    private val document = TextDocument(PieceTree.of("Hello"), history = stack)

    /** What the journal would hear, in order. */
    private val heard = mutableListOf<String>()

    init {
        document.addEditObserver(
            object : EditObserver {
                override fun beforeEdit(op: EditOp) {
                    heard += "op ${op.offset} -${op.deleted} +${op.inserted}"
                }

                override fun onStepSealed() {
                    heard += "seal"
                }

                override fun onUndo(opCount: Int) {
                    heard += "undo $opCount"
                }

                override fun onRedo(opCount: Int) {
                    heard += "redo $opCount"
                }
            },
        )
    }

    private fun type(text: String) {
        for (c in text) {
            document.replace(document.text.length, 0, c.toString())
            now += 100
        }
    }

    @Test
    fun `a seal is heard before the op that starts the next step, never after a lone run`() {
        type("ab")
        now += 5_000
        type("c")
        document.breakTypingRun()
        type("d")

        assertEquals(listOf("op 5 - +a", "op 6 - +b", "seal", "op 7 - +c", "seal", "op 8 - +d"), heard)
    }

    @Test
    fun `an explicit group is one step however many ops and however long it takes`() {
        type("x")
        document.beginEditGroup()
        document.replace(0, 0, "1")
        now += 10_000 // A chunked paste spans frames; the clock never splits a group.
        document.replace(0, 0, "2")
        document.endEditGroup()
        type("y")

        assertEquals(listOf("op 5 - +x", "seal", "op 0 - +1", "op 0 - +2", "seal", "op 8 - +y"), heard)
    }

    @Test
    fun `undo and redo are heard with their op counts before their ops`() {
        document.beginEditGroup()
        document.replace(0, 0, "1")
        document.replace(6, 0, "2")
        document.endEditGroup()
        heard.clear()

        document.undo()
        document.redo()

        assertEquals(listOf("undo 2", "op 6 -2 +", "op 0 -1 +", "redo 2", "op 0 - +1", "op 6 - +2"), heard)
    }

    @Test
    fun `after an undo the next edit starts a new step without a seal`() {
        type("ab")
        document.undo()
        heard.clear()
        type("c")

        assertEquals(listOf("op 5 - +c"), heard)
    }

    @Test
    fun `export and restore round-trip both stacks`() {
        type("abc")
        now += 5_000
        type("def")
        document.beginEditGroup()
        document.replace(0, 5, "Howdy")
        document.endEditGroup()
        document.undo()

        val exported = document.exportHistory()
        val restored = TextDocument(PieceTree.of(document.text.toString()))
        assertTrue(restored.restoreHistory(exported))

        assertEquals(walk(document), walk(restored))
    }

    @Test
    fun `restored steps are sealed, so the next keystroke is a step of its own`() {
        type("ab")
        val exported = document.exportHistory()
        assertTrue(exported.newestOpen)
        val restored = TextDocument(PieceTree.of("Helloab"), history = UndoStack(clock = { now }))
        restored.restoreHistory(exported)

        restored.replace(restored.text.length, 0, "c")
        restored.undo()

        assertEquals("Helloab", restored.text.toString())
    }

    @Test
    fun `the tail is the newest steps that fit, never a step with holes`() {
        type("a")
        now += 5_000
        type("b")
        now += 5_000
        type("c")
        val one = EditOp(0, "", "a").tailBytes

        val tail = document.exportHistory(maxBytes = 2 * one)

        assertEquals(listOf("b", "c"), tail.undo.map { it.single().inserted.toString() })
        assertTrue(tail.redo.isEmpty())
    }

    @Test
    fun `an open newest step is only open in the tail if it made it in`() {
        type("a")
        now += 5_000
        type("bbbb")

        assertFalse(document.exportHistory(maxBytes = 1).newestOpen)
        assertTrue(document.exportHistory().newestOpen)
    }

    @Test
    fun `steps that do not fit the text are refused`() {
        type("ab")
        val exported = document.exportHistory()

        val other = TextDocument(PieceTree.of("Goodbye"))
        assertFalse(other.restoreHistory(exported))
        assertFalse(other.canUndo)
    }

    @Test
    fun `random sessions export to stacks that undo and redo through the same texts`() {
        repeat(CASES) { seed ->
            val random = Random(seed)
            var clock = 0L
            val live = TextDocument(PieceTree.of("start"), history = UndoStack(clock = { clock }))
            repeat(random.nextInt(1, STEPS)) {
                clock += random.nextLong(0, 2_000)
                when (random.nextInt(8)) {
                    0 -> {
                        live.undo()
                    }

                    1 -> {
                        live.redo()
                    }

                    2 -> {
                        live.breakTypingRun()
                    }

                    3 -> {
                        live.beginEditGroup()
                        repeat(random.nextInt(1, 4)) { randomEdit(random, live) }
                        live.endEditGroup()
                    }

                    else -> {
                        randomEdit(random, live)
                    }
                }
            }
            val copy = TextDocument(PieceTree.of(live.text.toString()))
            assertTrue("seed $seed", copy.restoreHistory(live.exportHistory()))
            assertEquals("seed $seed", walk(live), walk(copy))
        }
    }

    private fun randomEdit(
        random: Random,
        doc: TextDocument,
    ) {
        val length = doc.text.length
        val at = random.nextInt(length + 1)
        val cut = random.nextInt(minOf(3, length - at) + 1)
        doc.replace(at, cut, if (random.nextBoolean()) "w${random.nextInt(9)}" else "")
    }

    /** Every text undo reaches, back to the oldest step, then every text redo reaches. */
    private fun walk(doc: TextDocument): List<String> {
        val texts = mutableListOf(doc.text.toString())
        while (doc.undo() != null) texts += doc.text.toString()
        while (doc.redo() != null) texts += doc.text.toString()
        return texts
    }

    private companion object {
        const val CASES = 200
        const val STEPS = 60
    }
}
