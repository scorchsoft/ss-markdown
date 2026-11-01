/*
 * Ported from Visual Studio Code,
 * src/vs/editor/test/common/model/pieceTreeTextBuffer/pieceTreeTextBuffer.test.ts.
 * Copyright (c) Microsoft Corporation, MIT License; see core/text/NOTICE.
 *
 * Each test keeps its VS Code name so a failure can be traced to the bug it
 * was written for. Lines and columns are converted from VS Code's 1-based
 * values to our 0-based ones. The CR cases run against normalised text
 * (a lone '\r' becomes '\n'), which is our model, so they now guard the
 * tree's structure rather than VS Code's CRLF repair code, which we dropped.
 */
package com.scorchsoft.ssmarkdown.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Random

class PieceTreeVsCodeTest {
    // region inserts and deletes

    @Test
    fun `basic insert-delete`() {
        val m = TextMirror("This is a document with some text.")
        m.insert(34, "This is some more text to insert at offset 34.")
        assertEquals(
            "This is a document with some text.This is some more text to insert at offset 34.",
            m.tree.toString(),
        )
        m.delete(42, 5)
        assertEquals("This is a document with some text.This is more text to insert at offset 34.", m.tree.toString())
        m.assertAll()
    }

    @Test
    fun `more inserts`() {
        val m = TextMirror()
        m
            .insert(0, "AAA")
            .insert(0, "BBB")
            .insert(6, "CCC")
            .insert(5, "DDD")
        assertEquals("BBBAADDDACCC", m.tree.toString())
        m.assertAll()
    }

    @Test
    fun `more deletes`() {
        val m = TextMirror("012345678")
        m.delete(8, 1)
        assertEquals("01234567", m.tree.toString())
        m.delete(0, 1).delete(5, 1).delete(5, 1)
        assertEquals("12345", m.tree.toString())
        m.delete(0, 5)
        assertEquals("", m.tree.toString())
        m.assertAll()
    }

    @Test
    fun `random test 1`() {
        TextMirror()
            .insert(0, "ceLPHmFzvCtFeHkCBej ")
            .insert(8, "gDCEfNYiBUNkSwtvB K ")
            .insert(38, "cyNcHxjNPPoehBJldLS ")
            .insert(59, "ejMx\nOTgWlbpeDExjOk ")
            .assertAll()
    }

    @Test
    fun `random test 2`() {
        TextMirror()
            .insert(0, "VgPG ")
            .insert(2, "DdWF ")
            .insert(0, "hUJc ")
            .insert(8, "lQEq ")
            .insert(10, "Gbtp ")
            .assertAll()
    }

    @Test
    fun `random test 3`() {
        TextMirror()
            .insert(0, "gYSz")
            .insert(1, "mDQe")
            .insert(1, "DTMQ")
            .insert(2, "GGZB")
            .insert(12, "wXpq")
            .assertAll()
    }

    @Test
    fun `random delete 1`() {
        TextMirror()
            .insert(0, "vfb")
            .insert(0, "zRq")
            .delete(5, 1)
            .insert(1, "UNw")
            .delete(4, 3)
            .delete(1, 4)
            .delete(0, 1)
            .assertAll()
    }

    @Test
    fun `random delete 2`() {
        TextMirror()
            .insert(0, "IDT")
            .insert(3, "wwA")
            .insert(3, "Gnr")
            .delete(6, 3)
            .insert(4, "eHp")
            .insert(1, "UAi")
            .insert(2, "FrR")
            .delete(6, 7)
            .delete(3, 5)
            .assertAll()
    }

    @Test
    fun `random delete 3`() {
        TextMirror()
            .insert(0, "PqM")
            .delete(1, 2)
            .insert(1, "zLc")
            .insert(0, "MEX")
            .insert(0, "jZh")
            .insert(8, "GwQ")
            .delete(5, 6)
            .insert(4, "ktw")
            .insert(5, "GVu")
            .insert(9, "jdm")
            .insert(15, "na\n")
            .delete(5, 8)
            .delete(3, 4)
            .assertAll()
    }

    @Test
    fun `random insert-delete CR bug 1`() {
        TextMirror("a")
            .delete(0, 1)
            .insert(0, "\r\r\n\n")
            .delete(2, 1)
            .insert(2, "\n\n\ra")
            .delete(3, 3)
            .insert(2, "\na\r\r")
            .insert(6, "\ra\n\n")
            .insert(0, "aa\n\n")
            .insert(5, "\n\na\r")
            .assertAll()
    }

    // endregion
    // region prefix sum for line feed

    @Test
    fun `prefix sum basic`() {
        val tree = PieceTree.of("1\n2\n3\n4")
        assertEquals(4, tree.lineCount)
        val positions = listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1, 2 to 0, 2 to 1, 3 to 0)
        for ((offset, expected) in positions.withIndex()) {
            assertEquals(TextPosition(expected.first, expected.second), tree.positionAt(offset))
            assertEquals(offset, tree.offsetAt(expected.first, expected.second))
        }
        tree.checkInvariants()
    }

    @Test
    fun `prefix sum append`() {
        val tree = PieceTree.of("a\nb\nc\nde")
        tree.insert(8, "fh\ni\njk")
        assertEquals(6, tree.lineCount)
        assertEquals(TextPosition(3, 3), tree.positionAt(9))
        assertEquals(0, tree.offsetAt(0, 0))
        tree.checkInvariants()
    }

    @Test
    fun `prefix sum insert`() {
        val tree = PieceTree.of("a\nb\nc\nde")
        tree.insert(7, "fh\ni\njk")
        assertEquals(6, tree.lineCount)
        assertPositions(
            tree,
            6 to (3 to 0),
            7 to (3 to 1),
            8 to (3 to 2),
            9 to (3 to 3),
            12 to (5 to 0),
            13 to (5 to 1),
            14 to (5 to 2),
        )
        tree.checkInvariants()
    }

    @Test
    fun `prefix sum delete`() {
        val tree = PieceTree.of("a\nb\nc\ndefh\ni\njk")
        tree.delete(7, 2)
        assertEquals("a\nb\nc\ndh\ni\njk", tree.toString())
        assertEquals(6, tree.lineCount)
        assertPositions(
            tree,
            6 to (3 to 0),
            7 to (3 to 1),
            8 to (3 to 2),
            9 to (4 to 0),
            11 to (5 to 0),
            12 to (5 to 1),
            13 to (5 to 2),
        )
        tree.checkInvariants()
    }

    @Test
    fun `prefix sum add+delete 1`() {
        val tree = PieceTree.of("a\nb\nc\nde")
        tree.insert(8, "fh\ni\njk")
        tree.delete(7, 2)
        assertEquals("a\nb\nc\ndh\ni\njk", tree.toString())
        assertPositions(
            tree,
            6 to (3 to 0),
            7 to (3 to 1),
            8 to (3 to 2),
            9 to (4 to 0),
            11 to (5 to 0),
            12 to (5 to 1),
            13 to (5 to 2),
        )
        tree.checkInvariants()
    }

    @Test
    fun `insert random bug 1 - removeValues count is 1 based`() {
        TextMirror()
            .insert(0, " ZX \n Z\nZ\n YZ\nY\nZXX ")
            .insert(14, "X ZZ\nYZZYZXXY Y XY\n ")
            .assertAll()
    }

    @Test
    fun `insert random bug 2 - initialize does not deep copy`() {
        TextMirror()
            .insert(0, "ZYZ\nYY XY\nX \nZ Y \nZ ")
            .insert(3, "XXY \n\nY Y YYY  ZYXY ")
            .assertAll()
    }

    @Test
    fun `delete random bug 1 - line feed count on a single piece`() {
        TextMirror()
            .insert(0, "ba\na\nca\nba\ncbab\ncaa ")
            .insert(13, "cca\naabb\ncac\nccc\nab ")
            .delete(5, 8)
            .delete(30, 2)
            .insert(24, "cbbacccbac\nbaaab\n\nc ")
            .delete(29, 3)
            .delete(23, 9)
            .delete(21, 5)
            .delete(30, 3)
            .insert(3, "cb\nac\nc\n\nacc\nbb\nb\nc ")
            .delete(19, 5)
            .insert(18, "\nbb\n\nacbc\ncbb\nc\nbb\n ")
            .insert(65, "cbccbac\nbc\n\nccabba\n ")
            .insert(77, "a\ncacb\n\nac\n\n\n\n\nabab ")
            .delete(30, 9)
            .insert(45, "b\n\nc\nba\n\nbbbba\n\naa\n ")
            .insert(82, "ab\nbb\ncabacab\ncbc\na ")
            .delete(123, 9)
            .delete(71, 2)
            .insert(33, "acaa\nacb\n\naa\n\nc\n\n\n\n ")
            .assertAll()
    }

    @Test
    fun `delete random bug rb tree 1`() {
        TextMirror()
            .insert(0, "YXXZ\n\nYY\n")
            .delete(0, 5)
            .insert(0, "ZXYY\nX\nZ\n")
            .insert(10, "\nXY\nYXYXY")
            .assertAll()
    }

    @Test
    fun `delete random bug rb tree 2`() {
        TextMirror()
            .insert(0, "YXXZ\n\nYY\n")
            .insert(0, "ZXYY\nX\nZ\n")
            .insert(10, "\nXY\nYXYXY")
            .insert(8, "YZXY\nZ\nYX")
            .insert(12, "XX\nXXYXYZ")
            .delete(0, 4)
            .assertAll()
    }

    @Test
    fun `delete random bug rb tree 3`() {
        TextMirror()
            .insert(0, "YXXZ\n\nYY\n")
            .delete(7, 2)
            .delete(6, 1)
            .delete(0, 5)
            .insert(0, "ZXYY\nX\nZ\n")
            .insert(10, "\nXY\nYXYXY")
            .insert(8, "YZXY\nZ\nYX")
            .insert(12, "XX\nXXYXYZ")
            .delete(0, 4)
            .delete(30, 3)
            .assertAll()
    }

    // endregion
    // region offset to position, text in range, line content

    @Test
    fun `offset 2 position random tests bug 1`() {
        TextMirror()
            .insert(0, "huuyYzUfKOENwGgZLqn ")
            .delete(18, 2)
            .delete(3, 1)
            .delete(12, 4)
            .insert(3, "hMbnVEdTSdhLlPevXKF ")
            .delete(22, 8)
            .insert(4, "S umSnYrqOmOAV\nEbZJ ")
            .assertAll()
    }

    @Test
    fun `get content in range`() {
        val tree = PieceTree.of("a\nb\nc\nde")
        tree.insert(8, "fh\ni\njk")
        tree.delete(7, 2)
        val expected = listOf("a\n", "b\n", "c\n", "dh\n", "i\n", "jk")
        for ((line, text) in expected.withIndex()) {
            val start = tree.lineStartOffset(line)
            assertEquals(text, tree.getText(start, start + text.length))
        }
        tree.checkInvariants()
    }

    @Test
    fun `random test value in range`() {
        TextMirror()
            .insert(0, "ZXXY")
            .insert(1, "XZZY")
            .insert(5, "\nX\n\n")
            .insert(3, "\nXX\n")
            .insert(12, "YYYX")
            .assertAll()
    }

    @Test
    fun `random test value in range exception`() {
        val m =
            TextMirror()
                .insert(0, "XZ\nZ")
                .delete(0, 3)
                .delete(0, 1)
                .insert(0, "ZYX\n")
                .delete(0, 4)
        assertEquals("", m.tree.getText(0, 0))
        m.assertAll()
    }

    @Test
    fun `get text in range random tests bug 2`() {
        TextMirror()
            .insert(0, "xfouRDZwdAHjVXJAMV\n ")
            .insert(16, "dBGndxpFZBEAIKykYYx ")
            .delete(7, 6)
            .delete(9, 7)
            .delete(17, 6)
            .delete(0, 4)
            .insert(9, "qvEFXCNvVkWgvykahYt ")
            .delete(4, 6)
            .insert(11, "OcSChUYT\nzPEBOpsGmR ")
            .insert(15, "KJCozaXTvkE\nxnqAeTz ")
            .assertAll()
    }

    @Test
    fun `get line content`() {
        val tree = PieceTree.of("1")
        assertEquals("1", tree.lineContent(0))
        tree.insert(1, "2")
        assertEquals("12", tree.lineContent(0))
    }

    @Test
    fun `get line content after inserts and deletes`() {
        val tree = PieceTree.of("a\nb\nc\nde")
        tree.insert(8, "fh\ni\njk")
        tree.delete(7, 2)
        assertEquals(listOf("a", "b", "c", "dh", "i", "jk"), (0 until tree.lineCount).map(tree::lineContent))
    }

    @Test
    fun `get line content random 2`() {
        TextMirror()
            .insert(0, "DZoQ tglPCRHMltejRI ")
            .insert(10, "JRXiyYqJ qqdcmbfkKX ")
            .delete(16, 3)
            .delete(25, 1)
            .insert(18, "vH\nNlvfqQJPm\nSFkhMc ")
            .assertAll()
    }

    // endregion
    // region CRLF (normalised)

    @Test
    fun `delete CR in CRLF 1`() {
        // "a\r\nb" is "a\nb" in memory; deleting "a\n" leaves one line.
        val m = TextMirror().insert(0, "a\r\nb").delete(0, 2)
        assertEquals(1, m.tree.lineCount)
        m.assertAll()
    }

    @Test
    fun `splitting large change buffer`() {
        TextMirror()
            .insert(0, "WUZ\nXVZY\n")
            .insert(8, "\r\r\nZXUWVW")
            .delete(10, 6)
            .delete(10, 1)
            .insert(4, "VX\r\r\nWZVZ")
            .delete(11, 3)
            .delete(10, 2)
            .delete(8, 0)
            .insert(0, "VZXXZYZX\r")
            .assertAll()
    }

    // endregion
    // region random is unsupervised (seeded here, so a failure reproduces)

    @Test
    fun `random insert delete`() {
        val random = Random(SEED)
        val m = TextMirror()
        repeat(1_000) { randomEdit(random, m, insertLength = 100) }
        m.assertAll()
    }

    @Test
    fun `random chunks`() {
        val random = Random(SEED + 1)
        val builder = TextBufferBuilder()
        val chunks = List(5) { randomString(random, 1_000) }
        chunks.forEach(builder::append)
        val m = TextMirror(chunks.joinToString(""))
        // Rebuild the mirror's tree from separate chunks, as VS Code's test does.
        val tree = builder.build().text
        assertEquals(m.tree.toString(), tree.toString())
        repeat(1_000) { randomEdit(random, m, insertLength = 100) }
        m.assertAll()
    }

    @Test
    fun `random chunks 2`() {
        val random = Random(SEED + 2)
        val m = TextMirror(randomString(random, 1_000))
        repeat(50) {
            randomEdit(random, m, insertLength = 30)
            m.assertAll()
        }
    }

    // endregion
    // region snapshot

    @Test
    fun `bug 45564 - pieces must be immutable`() {
        val tree = PieceTree.of("\n")
        tree.insert(1, "!")
        val snapshot = tree.snapshot()
        val snapshot1 = tree.snapshot()
        assertEquals(tree.toString(), snapshot.toString())
        tree.delete(1, 1)
        tree.insert(1, "!")
        assertEquals(tree.toString(), snapshot1.toString())
    }

    @Test
    fun `immutable snapshot 1`() {
        val tree = PieceTree.of("abc\ndef")
        val snapshot = tree.snapshot()
        tree.delete(4, 3)
        tree.delete(0, 4)
        tree.insert(0, "abc\ndef")
        assertEquals(tree.toString(), snapshot.toString())
    }

    @Test
    fun `immutable snapshot 2`() {
        val tree = PieceTree.of("abc\ndef")
        val snapshot = tree.snapshot()
        tree.insert(4, "!")
        tree.delete(4, 1)
        assertEquals(tree.toString(), snapshot.toString())
    }

    @Test
    fun `immutable snapshot 3`() {
        val tree = PieceTree.of("abc\ndef")
        tree.insert(7, "!")
        val snapshot = tree.snapshot()
        tree.insert(8, "!")
        assertNotEquals(tree.toString(), snapshot.toString())
        assertEquals("abc\ndef!", snapshot.toString())
    }

    // endregion

    private fun assertPositions(
        tree: PieceTree,
        vararg expected: Pair<Int, Pair<Int, Int>>,
    ) {
        for ((offset, position) in expected) {
            assertEquals("position of $offset", TextPosition(position.first, position.second), tree.positionAt(offset))
            assertEquals("offset of $position", offset, tree.offsetAt(position.first, position.second))
        }
    }

    private fun randomEdit(
        random: Random,
        m: TextMirror,
        insertLength: Int,
    ) {
        val length = m.reference.length
        if (random.nextDouble() < INSERT_RATIO) {
            m.insert(random.nextInt(length + 1), randomString(random, insertLength))
        } else if (length > 0) {
            val offset = random.nextInt(length)
            m.delete(offset, minOf(length - offset, random.nextInt(10)))
        }
    }

    private fun randomString(
        random: Random,
        length: Int,
    ): String = String(CharArray(length - 1) { ALPHABET[random.nextInt(ALPHABET.length)] })

    private companion object {
        const val SEED = 45_564L
        const val INSERT_RATIO = 0.6
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n"
    }
}
