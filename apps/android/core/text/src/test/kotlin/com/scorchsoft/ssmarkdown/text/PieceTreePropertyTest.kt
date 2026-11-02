package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * Random edits against a [StringBuilder] reference, across many seeds.
 *
 * Seeded `java.util.Random` rather than a property-testing library: the
 * generators are a dozen lines, a failure prints the seed and op number to
 * replay, and the module stays free of test dependencies beyond JUnit.
 *
 * Default runs total ~250k ops so `check` stays fast. Pass
 * `-Pssmarkdown.heavyTests=true` for ~4M ops (~15 s).
 */
class PieceTreePropertyTest {
    private val heavy = System.getProperty("ssmarkdown.heavyTests") == "true"
    private val scale = if (heavy) HEAVY_SCALE else 1

    @Test
    fun `random edits match a StringBuilder`() {
        for (seed in 1L..SEEDS) {
            runSeed(seed, ops = OPS_PER_SEED * scale)
        }
    }

    @Test
    fun `random edits on a loaded multi-chunk document match a StringBuilder`() {
        val random = Random(LOADED_SEED)
        val initial = StringBuilder()
        while (initial.length < 3 * TextBuffer.CAPACITY) initial.append(TextGenerator.text(random, 200))
        runSeed(LOADED_SEED, ops = OPS_PER_SEED * scale, initial = initial.toString())
    }

    @Test
    fun `undo and redo return to every reference state`() {
        for (seed in 1L..UNDO_SEEDS * scale) {
            val random = Random(seed)
            val document =
                TextDocument(PieceTree.of(TextGenerator.text(random, 300)), history = UndoStack(typingRunMillis = 0))
            val states = mutableListOf(document.text.toString())
            repeat(UNDO_OPS) {
                val length = document.text.length
                val offset = random.nextInt(length + 1)
                val count = if (length == offset) 0 else random.nextInt(minOf(length - offset, MAX_DELETE) + 1)
                document.breakTypingRun()
                if (!document.replace(offset, count, TextGenerator.text(random, random.nextInt(12))).isNoOp) {
                    states += document.text.toString()
                }
            }
            for (i in states.indices.reversed()) {
                assertEquals("seed $seed undo to state $i", states[i], document.text.toString())
                document.undo()
            }
            for (i in 1 until states.size) {
                document.redo()
                assertEquals("seed $seed redo to state $i", states[i], document.text.toString())
            }
            document.text.checkInvariants()
        }
    }

    @Test
    fun `compaction at random points preserves the text`() {
        val random = Random(COMPACTION_SEED)
        val mirror = TextMirror(TextGenerator.text(random, 5_000))
        repeat(COMPACTION_ROUNDS) {
            repeat(2_000) { edit(random, mirror) }
            mirror.tree.compact()
            mirror.assertAll()
        }
    }

    private fun runSeed(
        seed: Long,
        ops: Int,
        initial: String = "",
    ) {
        val random = Random(seed)
        val mirror = TextMirror(initial)
        for (op in 1..ops) {
            try {
                edit(random, mirror)
                checkRandomLookups(random, mirror)
                if (op % FULL_CHECK_EVERY == 0) mirror.assertText()
            } catch (e: AssertionError) {
                throw AssertionError("seed $seed, op $op: ${e.message}", e)
            }
        }
        mirror.assertAll()
    }

    private fun edit(
        random: Random,
        mirror: TextMirror,
    ) {
        val length = mirror.reference.length
        val insert = length == 0 || random.nextInt(100) < insertPercent(length)
        if (insert) {
            val offset = random.nextInt(length + 1)
            val size = if (random.nextInt(50) == 0) random.nextInt(4_000) else random.nextInt(16)
            mirror.insert(offset, TextGenerator.text(random, size))
        } else {
            val offset = random.nextInt(length)
            val size = if (random.nextInt(50) == 0) random.nextInt(2_000) else random.nextInt(16)
            mirror.delete(offset, minOf(size, length - offset))
        }
    }

    /** Keeps documents between a few hundred and a few thousand chars, where structure changes most. */
    private fun insertPercent(length: Int): Int =
        when {
            length < 500 -> 70
            length > 8_000 -> 30
            else -> 52
        }

    private fun checkRandomLookups(
        random: Random,
        mirror: TextMirror,
    ) {
        val tree = mirror.tree
        val reference = mirror.reference
        assertEquals(reference.length, tree.length)
        val offset = random.nextInt(reference.length + 1)
        val position = tree.positionAt(offset)
        assertEquals("round trip at $offset", offset, tree.offsetAt(position.line, position.column))
        val lineStart = reference.lastIndexOf("\n", offset - 1) + 1
        assertEquals(offset - lineStart, position.column)
        if (reference.isNotEmpty()) {
            val index = random.nextInt(reference.length)
            assertEquals(reference[index], tree[index])
        }
        val line = random.nextInt(tree.lineCount)
        val start = tree.lineStartOffset(line)
        assertEquals(TextPosition(line, 0), tree.positionAt(start))
        val end = reference.indexOf('\n', start).let { if (it < 0) reference.length else it }
        assertEquals(reference.substring(start, end), tree.lineContent(line))
    }

    private companion object {
        const val SEEDS = 20L
        const val OPS_PER_SEED = 10_000
        const val HEAVY_SCALE = 20
        const val FULL_CHECK_EVERY = 500
        const val LOADED_SEED = 9_001L
        const val UNDO_SEEDS = 20L
        const val UNDO_OPS = 300
        const val MAX_DELETE = 40
        const val COMPACTION_SEED = 77L
        const val COMPACTION_ROUNDS = 5
    }
}

/** Text that exercises the awkward cases: CR, CRLF, surrogate pairs, ZWJ emoji, combining marks. */
object TextGenerator {
    private val atoms =
        listOf(
            "a",
            "b",
            "c",
            "x",
            "y",
            " ",
            " ",
            "#",
            "*",
            "`",
            "\n",
            "\n",
            "\r\n",
            "\r",
            "😀", // 😀 a surrogate pair
            "👩‍💻", // 👩‍💻 a ZWJ sequence
            "🇬🇧", // 🇬🇧 a flag
            "é", // e + combining acute
            "é",
            "日本",
        )

    /** Roughly [length] chars of random text (atoms are 1 to 5 chars). */
    fun text(
        random: Random,
        length: Int,
    ): String {
        val out = StringBuilder(length + 5)
        while (out.length < length) out.append(atoms[random.nextInt(atoms.size)])
        return out.toString()
    }
}
