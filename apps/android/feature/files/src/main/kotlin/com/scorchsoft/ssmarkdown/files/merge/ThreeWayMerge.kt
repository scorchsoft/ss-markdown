package com.scorchsoft.ssmarkdown.files.merge

/**
 * A line-based three-way merge with `git merge-file` semantics
 * (docs/robustness.md §2a, "Merge my edits"): the classic diff3 walk.
 *
 * Lines unchanged on both sides are *stable*. Between stable runs lies an
 * unstable chunk: if only one side changed it, that side wins; if both
 * changed it identically, the shared change is taken once; otherwise it
 * is a [MergeChunk.Conflict]. As in git, edits that touch or abut the
 * same region with no unchanged line between them conflict, and two
 * different insertions at the same place conflict. Lines common to the
 * start or end of both sides of a conflict are moved out of it, as git's
 * default ("zealous") level does, so the conflict shows only what differs.
 *
 * Pure Kotlin; property-tested in `ThreeWayMergePropertyTest`.
 */
class ThreeWayMerge internal constructor(
    private val diff: LineDiff,
) {
    constructor() : this(LineDiff())

    /** Merges the change from [base] to [ours] with the change from [base] to [theirs]. */
    fun merge(
        base: CharSequence,
        ours: CharSequence,
        theirs: CharSequence,
    ): MergeResult {
        val baseLines = Lines.split(base)
        val ourLines = Lines.split(ours)
        val theirLines = Lines.split(theirs)
        val (b, o, t) = Lines.intern(baseLines, ourLines, theirLines)
        val walk = Walk(baseLines, ourLines, theirLines, diff.matches(b, o), diff.matches(b, t))
        return MergeResult(walk.run())
    }

    private class Walk(
        val base: List<String>,
        val ours: List<String>,
        val theirs: List<String>,
        val toOurs: IntArray,
        val toTheirs: IntArray,
    ) {
        private val out = ArrayList<MergeChunk>()
        private var i = 0
        private var a = 0
        private var b = 0

        fun run(): List<MergeChunk> {
            while (i < base.size || a < ours.size || b < theirs.size) {
                val stable = stableRun()
                if (stable > 0) {
                    out += MergeChunk.Resolved(base.subList(i, i + stable), changed = false)
                    i += stable
                    a += stable
                    b += stable
                } else {
                    unstableChunk()
                }
            }
            return out
        }

        private fun stableRun(): Int {
            var n = 0
            while (i + n < base.size && toOurs[i + n] == a + n && toTheirs[i + n] == b + n) n++
            return n
        }

        private fun unstableChunk() {
            var next = i
            while (next < base.size && (toOurs[next] < 0 || toTheirs[next] < 0)) next++
            val nextA = if (next < base.size) toOurs[next] else ours.size
            val nextB = if (next < base.size) toTheirs[next] else theirs.size
            classify(base.subList(i, next), ours.subList(a, nextA), theirs.subList(b, nextB))
            i = next
            a = nextA
            b = nextB
        }

        private fun classify(
            was: List<String>,
            mine: List<String>,
            file: List<String>,
        ) {
            when {
                mine == was -> out += MergeChunk.Resolved(file, changed = true)
                file == was || mine == file -> out += MergeChunk.Resolved(mine, changed = true)
                else -> addConflict(was, mine, file)
            }
        }

        /** Moves lines both sides share at the start and end out of the conflict. */
        private fun addConflict(
            was: List<String>,
            mine: List<String>,
            file: List<String>,
        ) {
            var head = 0
            while (head < mine.size && head < file.size && mine[head] == file[head]) head++
            var tail = 0
            while (tail < mine.size - head && tail < file.size - head &&
                mine[mine.size - 1 - tail] == file[file.size - 1 - tail]
            ) {
                tail++
            }
            if (head > 0) out += MergeChunk.Resolved(mine.subList(0, head), changed = true)
            out += MergeChunk.Conflict(was, mine.subList(head, mine.size - tail), file.subList(head, file.size - tail))
            if (tail > 0) out += MergeChunk.Resolved(mine.subList(mine.size - tail, mine.size), changed = true)
        }
    }
}
