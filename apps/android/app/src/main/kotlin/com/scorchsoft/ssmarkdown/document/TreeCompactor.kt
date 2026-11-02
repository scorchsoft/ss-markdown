package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.text.PieceTree
import kotlinx.coroutines.launch

/**
 * Keeps the piece tree from fragmenting over a long session (performance
 * review PERF-F; docs/robustness.md, "Text model"). Every keystroke that
 * is not an append splits a piece, and snapshots (the Preview and the
 * outline take one per run of edits) cost O(pieces), so without this the
 * cost of each keystroke grew for as long as the file stayed open.
 *
 * After each run of edits, [maybeCompact] checks [PieceTree.needsCompaction]
 * (O(1)). Past the line it begins a compaction (O(pieces) here), copies
 * the text into fresh chunks on the worker, and swaps them in on the main
 * thread if nothing was typed meanwhile; otherwise the next run of edits
 * tries again. One at a time.
 */
class TreeCompactor(
    private val tree: PieceTree,
    private val threads: DocumentThreads,
) {
    private var running = false

    /** Compactions swapped in: the PERF-F regression statistic. */
    var compactions: Int = 0
        private set

    /** Main thread, after a run of edits. */
    fun maybeCompact() {
        if (running || !tree.needsCompaction) return
        running = true
        val compaction = tree.beginCompaction()
        threads.main.launch(threads.worker) {
            compaction.pack()
            threads.main.launch {
                running = false
                if (tree.finishCompaction(compaction)) compactions++
            }
        }
    }
}
