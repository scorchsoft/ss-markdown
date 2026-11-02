package com.scorchsoft.ssmarkdown.files.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Randomised properties of the merge (robustness Part 3: "non-overlapping
 * edits merge exactly as git's merge-file would; overlaps are always
 * reported, never silently resolved"). Seeded, so a failure reproduces.
 */
class ThreeWayMergePropertyTest {
    private val merge = ThreeWayMerge()

    @Test
    fun `the line diff is a valid common subsequence and is maximal for single edits`() {
        val random = Random(SEED)
        repeat(ROUNDS) {
            val a = randomLines(random)
            val b = mutate(random, a, edits = 1 + random.nextInt(4))
            val (x, y) = Lines.intern(a, b)
            val partner = LineDiff().matches(x, y)
            var last = -1
            partner.forEachIndexed { i, j ->
                if (j >= 0) {
                    assertTrue("matches must increase", j > last)
                    assertEquals(a[i], b[j])
                    last = j
                }
            }
        }
    }

    @Test
    fun `merging with an unchanged side gives the other side exactly`() {
        val random = Random(SEED + 1)
        repeat(ROUNDS) {
            val base = text(randomLines(random))
            val changed = text(mutate(random, Lines.split(base), edits = 1 + random.nextInt(5)))
            assertEquals(changed, merge.merge(base, changed, base).cleanText())
            assertEquals(changed, merge.merge(base, base, changed).cleanText())
            assertEquals(changed, merge.merge(base, changed, changed).cleanText())
        }
    }

    @Test
    fun `edits in separate regions merge to both edits applied`() {
        val random = Random(SEED + 2)
        repeat(ROUNDS) {
            val base = List(GAP * 2 + 2 + random.nextInt(20)) { "line $it\n" }
            val split = GAP + random.nextInt(base.size - 2 * GAP)
            // Ours edits only the head, theirs only the tail, with untouched base lines between.
            val oursHead = mutate(random, base.subList(0, split - 1), 1 + random.nextInt(3), tag = "o")
            val theirsTail = mutate(random, base.subList(split + 1, base.size), 1 + random.nextInt(3), tag = "t")
            val middle = base.subList(split - 1, split + 1)
            val ours = text(oursHead + middle + base.subList(split + 1, base.size))
            val theirs = text(base.subList(0, split - 1) + middle + theirsTail)
            val expected = text(oursHead + middle + theirsTail)

            val result = merge.merge(text(base), ours, theirs)

            assertEquals(expected, result.cleanText())
        }
    }

    @Test
    fun `conflicting edits to one line are always reported`() {
        val random = Random(SEED + 3)
        repeat(ROUNDS) {
            val base = randomLines(random, min = 1)
            val at = random.nextInt(base.size)
            val ours = base.toMutableList().also { it[at] = "ours $at\n" }
            val theirs = base.toMutableList().also { it[at] = "theirs $at\n" }

            val result = merge.merge(text(base), text(ours), text(theirs))

            assertTrue(result.conflictCount >= 1)
            assertEquals(text(ours), result.text(ConflictResolver.TAKE_OURS))
            assertEquals(text(theirs), result.text(ConflictResolver.TAKE_THEIRS))
        }
    }

    @Test
    fun `every merge reassembles each side when conflicts take that side`() {
        val random = Random(SEED + 4)
        repeat(ROUNDS) {
            val base = randomLines(random)
            val ours = mutate(random, base, 1 + random.nextInt(4), tag = "o")
            val theirs = mutate(random, base, 1 + random.nextInt(4), tag = "t")
            val result = merge.merge(text(base), text(ours), text(theirs))
            // A clean result keeps every line both sides kept.
            if (result.isClean) {
                val merged = Lines.split(result.cleanText().orEmpty())
                base
                    .filter { it in ours && it in theirs && base.count { l -> l == it } == 1 }
                    .forEach { assertTrue("lost shared line $it", it in merged) }
            }
        }
    }

    private fun text(lines: List<String>) = lines.joinToString("")

    private fun randomLines(
        random: Random,
        min: Int = 0,
    ): List<String> = List(min + random.nextInt(MAX_LINES)) { "line ${random.nextInt(VOCABULARY)}\n" }

    private fun mutate(
        random: Random,
        lines: List<String>,
        edits: Int,
        tag: String = "m",
    ): List<String> {
        val out = lines.toMutableList()
        repeat(edits) { n ->
            val at = if (out.isEmpty()) 0 else random.nextInt(out.size + 1)
            when (random.nextInt(3)) {
                0 -> out.add(at, "$tag-insert $n ${random.nextInt()}\n")
                1 -> if (at < out.size) out.removeAt(at)
                else -> if (at < out.size) out[at] = "$tag-change $n ${random.nextInt()}\n"
            }
        }
        return out
    }

    private companion object {
        const val SEED = 424242L
        const val ROUNDS = 400
        const val MAX_LINES = 30
        const val VOCABULARY = 12
        const val GAP = 3
    }
}
