package com.scorchsoft.ssmarkdown.markdown

import com.scorchsoft.ssmarkdown.text.PieceTree
import org.junit.Assert.assertEquals
import org.junit.Test

/** The adapters the editor will use: the live piece tree and a snapshot of it. */
class LineSourceTest {
    @Test
    fun `the cache reads a live piece tree and follows its edits`() {
        val tree = PieceTree.of("# One\ntext\n```\ncode")
        val cache = IncrementalTokenizer(tree.asLineSource())
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(listOf(Heading(0, 1, "One")), cache.outline.headings())
        assertEquals(LineKind.CODE, cache.resultAt(3).kind)

        tree.insert(tree.lineStartOffset(1), "## Two\n")
        cache.invalidate(1, 1, 2)
        cache.continueInIdle(Long.MAX_VALUE / 4)
        assertEquals(listOf(Heading(0, 1, "One"), Heading(1, 2, "Two")), cache.outline.headings())
        assertEquals(1..4, cache.outline.sectionRange(1))
    }

    @Test
    fun `a snapshot tokenises the same as the tree it came from`() {
        val tree = PieceTree.of("> quote\n- [x] done\n\$\$\nx\n\$\$")
        val fromTree = fullyTokenized(tree.asLineSource())
        val fromSnapshot = fullyTokenized(tree.snapshot().asLineSource())
        for (line in 0 until tree.lineCount) {
            assertEquals(
                true,
                fromTree.resultAt(line).sameAs(fromSnapshot.resultAt(line)),
            )
        }
    }
}
