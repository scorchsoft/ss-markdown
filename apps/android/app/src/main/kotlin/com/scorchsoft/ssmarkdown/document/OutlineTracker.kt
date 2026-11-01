package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.markdown.Heading
import com.scorchsoft.ssmarkdown.markdown.IncrementalTokenizer
import com.scorchsoft.ssmarkdown.markdown.asLineSource
import com.scorchsoft.ssmarkdown.text.PieceTree

/**
 * The document's heading index for the Navigator, the breadcrumb and
 * "Copy section", kept current from the editor's line changes.
 *
 * It owns its own [IncrementalTokenizer] over the live tree (the editor's
 * colouring cache is private to the view). Work happens only in
 * [advance], which the host calls from idle frames with a small budget,
 * so a 10k-heading file never blocks a keystroke (quality-bar §4: ≤ 8 ms
 * of main-thread tokenising per frame).
 */
class OutlineTracker(
    private val tree: PieceTree,
) {
    private val tokenizer = IncrementalTokenizer(tree.asLineSource())

    /** Bumped whenever the visible outline may have changed, so Compose state can key on it. */
    var revision: Int = 0
        private set

    /** True once every line has been tokenised and the outline is exact. */
    val isComplete: Boolean get() = tokenizer.isComplete

    /** Records that lines `[firstLine, firstLine + removedBreaks]` became `addedBreaks + 1` lines. */
    fun onLinesChanged(
        firstLine: Int,
        removedBreaks: Int,
        addedBreaks: Int,
    ) {
        val from = firstLine.coerceIn(0, tokenizer.lineCount - 1)
        val removed = (removedBreaks + 1).coerceAtMost(tokenizer.lineCount - from)
        tokenizer.invalidate(from, removed, addedBreaks + 1)
        revision++
    }

    /** Everything changed (an edit applied behind the view's back): start again from line 0. */
    fun invalidateAll() {
        tokenizer.invalidate(0, tokenizer.lineCount, tree.lineCount)
        revision++
    }

    /** Tokenises for at most [budgetNanos]; returns true when the outline is complete. */
    fun advance(budgetNanos: Long = DEFAULT_BUDGET_NANOS): Boolean {
        if (tokenizer.isComplete) return true
        tokenizer.continueInIdle(budgetNanos)
        revision++
        return tokenizer.isComplete
    }

    /** Every heading, in document order. */
    fun headings(): List<Heading> = tokenizer.outline.headings()

    /** The headings containing [line], outermost first. */
    fun breadcrumbAt(line: Int): List<Heading> = tokenizer.outline.breadcrumbAt(line)

    /** The line range of the section headed on [headingLine], or null. */
    fun sectionRange(headingLine: Int): IntRange? = tokenizer.outline.sectionRange(headingLine)

    /** Lines in the tree, for the reading-position line. */
    val lineCount: Int get() = tree.lineCount

    companion object {
        /** 4 ms: half the per-frame tokenising budget, leaving the rest for the editor's colouring. */
        const val DEFAULT_BUDGET_NANOS: Long = 4_000_000
    }
}
