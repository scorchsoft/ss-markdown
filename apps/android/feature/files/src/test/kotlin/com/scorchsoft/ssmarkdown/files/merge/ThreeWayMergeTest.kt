package com.scorchsoft.ssmarkdown.files.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Worked examples of `git merge-file` behaviour. */
class ThreeWayMergeTest {
    private val merge = ThreeWayMerge()

    @Test
    fun `changes separated by an unchanged line merge cleanly`() {
        val base = "a\nb\nc\nd\ne\n"
        val ours = "A\nb\nc\nd\ne\n"
        val theirs = "a\nb\nc\nd\nE\n"

        val result = merge.merge(base, ours, theirs)

        assertEquals("A\nb\nc\nd\nE\n", result.cleanText())
        assertEquals(2, result.cleanChangeCount)
    }

    @Test
    fun `different edits to the same line conflict`() {
        val result = merge.merge("a\nb\nc\n", "a\nmine\nc\n", "a\ntheirs\nc\n")

        assertEquals(1, result.conflictCount)
        assertNull(result.cleanText())
        val conflict = result.chunks.filterIsInstance<MergeChunk.Conflict>().single()
        assertEquals(listOf("b\n"), conflict.base)
        assertEquals(listOf("mine\n"), conflict.ours)
        assertEquals(listOf("theirs\n"), conflict.theirs)
        assertEquals("a\nmine\nc\n", result.text(ConflictResolver.TAKE_OURS))
        assertEquals("a\ntheirs\nc\n", result.text(ConflictResolver.TAKE_THEIRS))
    }

    @Test
    fun `adjacent edits with no unchanged line between conflict, as in git`() {
        val result = merge.merge("a\nb\nc\n", "A\nb\nc\n", "a\nB\nc\n")

        assertEquals(1, result.conflictCount)
    }

    @Test
    fun `two different insertions at the same place conflict`() {
        val result = merge.merge("a\nb\n", "a\nmine\nb\n", "a\ntheirs\nb\n")

        assertEquals(1, result.conflictCount)
    }

    @Test
    fun `the same change on both sides is taken once`() {
        val result = merge.merge("a\nb\nc\n", "a\nX\nc\n", "a\nX\nc\n")

        assertEquals("a\nX\nc\n", result.cleanText())
    }

    @Test
    fun `lines both sides share are moved out of a conflict`() {
        val result = merge.merge("a\nb\nz\n", "a\nsame\nmine\nz\n", "a\nsame\ntheirs\nz\n")

        val conflict = result.chunks.filterIsInstance<MergeChunk.Conflict>().single()
        assertEquals(listOf("mine\n"), conflict.ours)
        assertEquals(listOf("theirs\n"), conflict.theirs)
        assertEquals("a\nsame\nmine\nz\n", result.text(ConflictResolver.TAKE_OURS))
    }

    @Test
    fun `a missing final newline survives`() {
        val result = merge.merge("a\nb", "A\nb", "a\nb")

        assertEquals("A\nb", result.cleanText())
    }

    @Test
    fun `deleting a line one side and editing elsewhere merges`() {
        val result = merge.merge("1\n2\n3\n4\n5\n", "1\n3\n4\n5\n", "1\n2\n3\n4\nfive\n")

        assertEquals("1\n3\n4\nfive\n", result.cleanText())
    }

    @Test
    fun `edits on empty texts`() {
        assertEquals("x\n", merge.merge("", "x\n", "").cleanText())
        assertTrue(merge.merge("", "x\n", "y\n").conflictCount == 1)
    }

    @Test
    fun `the line diff respects its edit cap by reporting everything changed`() {
        val a = IntArray(50) { it }
        val b = IntArray(50) { it + 1000 }
        val capped = LineDiff(maxEdits = 10).matches(a, b)
        assertTrue(capped.all { it == LineDiff.NONE })
    }
}
