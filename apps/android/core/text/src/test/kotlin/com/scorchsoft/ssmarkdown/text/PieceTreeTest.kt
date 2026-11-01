package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PieceTreeTest {
    @Test
    fun `an empty document has one empty line`() {
        val tree = PieceTree.empty()
        assertEquals(0, tree.length)
        assertEquals(1, tree.lineCount)
        assertEquals("", tree.lineContent(0))
        assertEquals(TextPosition(0, 0), tree.positionAt(0))
        assertEquals(0, tree.pieceCount)
    }

    @Test
    fun `line breaks are normalised on the way in`() {
        val tree = PieceTree.of("a\r\nb\rc\n")
        assertEquals("a\nb\nc\n", tree.toString())
        tree.insert(0, "x\r\ny\r")
        assertEquals("x\ny\na\nb\nc\n", tree.toString())
        assertEquals(6, tree.lineCount)
    }

    @Test
    fun `typing at the end of a typed run grows one piece`() {
        val tree = PieceTree.of("# Title\n\nBody text.")
        tree.insert(9, "x")
        val pieces = tree.pieceCount
        for (i in 1..200) tree.insert(9 + i, "y")
        assertEquals(pieces, tree.pieceCount)
        assertTrue(tree.toString().startsWith("# Title\n\nx" + "y".repeat(200) + "Body"))
        tree.checkInvariants()
    }

    @Test
    fun `typing in the middle of a document also grows one piece`() {
        // The lookup can land on the right-hand piece at a boundary; the fast
        // path must still find the typed piece on its left.
        val tree = PieceTree.of("0123456789".repeat(100))
        tree.insert(500, "a")
        val pieces = tree.pieceCount
        for (i in 1..100) tree.insert(500 + i, "b")
        assertEquals(pieces, tree.pieceCount)
    }

    @Test
    fun `offsetAt clamps a column past the end of the line`() {
        val tree = PieceTree.of("long line\nab\n")
        assertEquals(12, tree.offsetAt(1, 40))
        assertEquals(2, tree.lineLength(1))
        assertEquals(0, tree.lineLength(2))
    }

    @Test
    fun `get reads sequentially and randomly`() {
        val text = "abc\ndef\nghi"
        val tree = PieceTree.of("")
        text.chunked(2).forEach { tree.insert(tree.length, it) }
        tree.insert(3, "XYZ")
        val expected = StringBuilder(text).insert(3, "XYZ").toString()
        assertEquals(expected, (0 until tree.length).map(tree::get).joinToString(""))
        assertEquals(expected[7], tree[7])
        assertEquals(expected[0], tree[0])
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun `get past the end throws`() {
        PieceTree.of("ab")[2]
    }

    @Test
    fun `slices are zero copy and reinsert without copying`() {
        val tree = PieceTree.of("hello\nbrave\nnew world")
        val slice = tree.slice(6, 12)
        assertEquals("brave\n", slice.toString())
        tree.delete(6, 6)
        assertEquals("hello\nnew world", tree.toString())
        tree.insert(6, slice)
        assertEquals("hello\nbrave\nnew world", tree.toString())
        assertEquals(3, tree.lineCount)
        tree.checkInvariants()
    }

    @Test
    fun `a snapshot from one document pastes into another`() {
        val source = PieceTree.of("one\ntwo\n")
        val target = PieceTree.of("[]")
        target.insert(1, source.snapshot())
        assertEquals("[one\ntwo\n]", target.toString())
        assertEquals(3, target.lineCount)
    }

    @Test
    fun `snapshot line access matches the tree`() {
        val tree = PieceTree.of("alpha\nbeta\n\ngamma")
        tree.insert(6, "B\nC")
        val snapshot = tree.snapshot()
        assertEquals(tree.lineCount, snapshot.lineCount)
        for (line in 0 until tree.lineCount) {
            assertEquals(tree.lineContent(line), snapshot.lineContent(line))
            assertEquals(tree.lineStartOffset(line), snapshot.lineStartOffset(line))
        }
        for (offset in 0..tree.length) assertEquals(tree.positionAt(offset), snapshot.positionAt(offset))
        assertEquals("Cbet", snapshot.subSequence(8, 12).toString())
    }

    @Test
    fun `a snapshot is readable on another thread while editing continues`() {
        val tree = PieceTree.of("x".repeat(200_000))
        val snapshot = tree.snapshot()
        val expected = snapshot.toString()
        var seen = ""
        val reader = Thread { seen = snapshot.toString() }
        reader.start()
        repeat(5_000) { tree.insert(it * 7 % tree.length, "y") }
        reader.join()
        assertEquals(expected, seen)
    }

    @Test
    fun `compaction keeps the text and removes the fragmentation`() {
        val tree = PieceTree.of("abcdefghij".repeat(1_000))
        repeat(3_000) { tree.insert((it * 7_919) % tree.length, "<$it>") }
        assertTrue(tree.needsCompaction)
        val before = tree.toString()
        val snapshot = tree.snapshot()
        tree.compact()
        assertEquals(before, tree.toString())
        assertEquals(before, snapshot.toString())
        assertFalse(tree.needsCompaction)
        assertEquals(1, tree.pieceCount)
        tree.checkInvariants()
        tree.insert(0, "still editable")
        assertTrue(tree.toString().startsWith("still editable"))
    }

    @Test
    fun `large inserts are split into capacity sized chunks without cutting a surrogate pair`() {
        val emoji = "😀"
        val big = "a" + emoji.repeat(TextBuffer.CAPACITY)
        val tree = PieceTree.empty()
        tree.insert(0, big)
        assertEquals(big, tree.toString())
        assertTrue(tree.pieceCount >= 3)
        tree.snapshot().pieces.forEach { assertFalse(it.charAt(it.length - 1).isHighSurrogate()) }
    }

    @Test
    fun `inserting an empty string is a no-op`() {
        val tree = PieceTree.of("abc")
        val empty = tree.slice(1, 1)
        assertSame(Snapshot.EMPTY, empty)
        tree.insert(1, "")
        tree.insert(1, empty)
        assertEquals("abc", tree.toString())
    }

    @Test
    fun `an off-thread compaction applies only to the text it copied (PERF-F)`() {
        val tree = PieceTree.of("abcdefghij".repeat(1_000))
        repeat(3_000) { tree.insert((it * 7_919) % tree.length, "<$it>") }
        assertTrue(tree.needsCompaction)
        val text = tree.toString()
        val stale = tree.beginCompaction().also { it.pack() }
        tree.insert(0, "y")
        assertFalse("an edit since it began makes it stale", tree.finishCompaction(stale))
        val fresh = tree.beginCompaction()
        assertFalse("not packed yet", tree.finishCompaction(fresh))
        fresh.pack()
        assertTrue(tree.finishCompaction(fresh))
        assertEquals("y$text", tree.toString())
        assertFalse(tree.needsCompaction)
        tree.checkInvariants()
    }
}
