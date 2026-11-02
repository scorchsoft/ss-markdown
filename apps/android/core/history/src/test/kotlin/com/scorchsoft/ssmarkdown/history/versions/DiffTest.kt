package com.scorchsoft.ssmarkdown.history.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DiffTest {
    @Test
    fun `myers finds a minimal script on random inputs`() {
        repeat(CASES) { seed ->
            val random = Random(seed)
            val a = IntArray(random.nextInt(0, 40)) { random.nextInt(4) }
            val b = IntArray(random.nextInt(0, 40)) { random.nextInt(4) }
            val regions = requireNotNull(MyersDiff.diff(a, b))
            assertEquals("seed $seed", b.toList(), apply(a, b, regions))
            val cost = regions.sumOf { (it.aEnd - it.aStart) + (it.bEnd - it.bStart) }
            assertEquals("seed $seed", a.size + b.size - 2 * lcs(a, b), cost)
        }
    }

    @Test
    fun `myers gives up when the budget runs out`() {
        val a = IntArray(2_000) { it }
        val b = IntArray(2_000) { it + 5_000 }
        assertNull(MyersDiff.diff(a, b, DiffBudget(maxEdits = 100)))
        assertNull(MyersDiff.diff(a, b, DiffBudget(maxWork = 1_000)))
    }

    @Test
    fun `line deltas round-trip exactly`() {
        repeat(CASES) { seed ->
            val random = Random(seed)
            val newer = Texts.document(random, random.nextInt(0, 40))
            var older = newer
            repeat(random.nextInt(1, 6)) { older = Texts.edit(random, older) }
            val delta = requireNotNull(LineDelta.encode(base = newer, target = older))
            assertEquals("seed $seed", older, LineDelta.apply(newer, delta.bytes))
        }
    }

    @Test
    fun `a delta that does not fit its base is refused`() {
        val delta = requireNotNull(LineDelta.encode(base = "a\nb\nc\n", target = "a\nX\nc\n")).bytes
        assertNull(LineDelta.apply("a\n", delta))
        assertNull(LineDelta.apply("a\nb\nc\n", delta.copyOf(delta.size - 1)))
    }

    @Test
    fun `hunks rebuild both sides and count lines`() {
        repeat(CASES) { seed ->
            val random = Random(seed)
            val old = Texts.document(random, random.nextInt(0, 60))
            var new = old
            repeat(random.nextInt(1, 5)) { new = Texts.edit(random, new) }
            val diff = LineDiff.compute(old, new, context = Int.MAX_VALUE / 4)
            val lines = diff.hunks.flatMap { it.lines }
            if (old != new) {
                assertEquals(old, lines.filter { it.type != DiffLineType.Added }.joinToString("") { it.text })
                assertEquals(new, lines.filter { it.type != DiffLineType.Removed }.joinToString("") { it.text })
            }
            assertEquals(lines.count { it.type == DiffLineType.Added }, diff.summary.added)
            assertEquals(lines.count { it.type == DiffLineType.Removed }, diff.summary.removed)
            assertEquals(diff.summary, LineDiff.summarize(old, new))
        }
    }

    @Test
    fun `hunks keep three lines of context and number lines from one`() {
        val old = (1..20).joinToString("") { "line $it\n" }
        val new = old.replace("line 10\n", "line ten\n")
        val hunk = LineDiff.compute(old, new).hunks.single()
        assertEquals(6, hunk.oldStart)
        assertEquals(7, hunk.oldCount)
        val removed = hunk.lines.single { it.type == DiffLineType.Removed }
        assertEquals(10, removed.oldNumber)
        assertEquals(LineChangeSummary(1, 1), LineDiff.compute(old, new).summary)
    }

    @Test
    fun `word refinement marks only the changed words and keeps the text`() {
        val diff = WordDiff.refine("The quick brown fox.", "The quick red fox!")
        assertEquals("The quick brown fox.", diff.old.joinToString("") { it.text })
        assertEquals("The quick red fox!", diff.new.joinToString("") { it.text })
        assertEquals(listOf("brown", "."), diff.old.filter { it.changed }.map { it.text })
        assertEquals(listOf("red", "!"), diff.new.filter { it.changed }.map { it.text })
        assertTrue(WordDiff.refine("", "").old.isEmpty())
    }

    private fun apply(
        a: IntArray,
        b: IntArray,
        regions: List<EditRegion>,
    ): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        for (r in regions) {
            while (i < r.aStart) out += a[i++]
            for (j in r.bStart until r.bEnd) out += b[j]
            i = r.aEnd
        }
        while (i < a.size) out += a[i++]
        return out
    }

    private fun lcs(
        a: IntArray,
        b: IntArray,
    ): Int {
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        return table[0][0]
    }

    private companion object {
        const val CASES = 500
    }
}
