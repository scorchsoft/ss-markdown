package com.scorchsoft.ssmarkdown.files.merge

/**
 * The longest common subsequence of two int sequences (interned lines),
 * as a map from each line of `a` to its partner in `b`, or -1.
 *
 * Myers' O(ND) greedy algorithm, after stripping the common prefix and
 * suffix (which is where nearly all of a typical document is). The edit
 * distance is capped at [maxEdits]: beyond it the remaining middle is
 * reported as wholly changed, which costs a three-way merge nothing in
 * safety (overlaps become a conflict, never a silent pick) and bounds
 * memory at O(maxEdits²) on a phone.
 */
internal class LineDiff(
    private val maxEdits: Int = DEFAULT_MAX_EDITS,
) {
    /** For each index of [a], the matching index in [b], or -1 if that line was deleted or changed. */
    fun matches(
        a: IntArray,
        b: IntArray,
    ): IntArray {
        val partner = IntArray(a.size) { NONE }
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) {
            partner[prefix] = prefix
            prefix++
        }
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) {
            partner[a.size - 1 - suffix] = b.size - 1 - suffix
            suffix++
        }
        myers(Window(a, prefix, a.size - suffix), Window(b, prefix, b.size - suffix), partner)
        return partner
    }

    /** A slice of a sequence: `values[from until to]`. */
    private class Window(
        val values: IntArray,
        val from: Int,
        val to: Int,
    ) {
        val size get() = to - from

        operator fun get(i: Int) = values[from + i]
    }

    private fun myers(
        a: Window,
        b: Window,
        partner: IntArray,
    ) {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return
        val limit = minOf(n + m, maxEdits)
        val offset = limit + 1
        val v = IntArray(2 * offset + 1) // k from -(limit + 1) to limit + 1
        val trace = ArrayList<IntArray>()
        for (d in 0..limit) {
            trace += v.copyOfRange(offset - d - 1, offset + d + 2)
            for (k in -d..d step 2) {
                var x =
                    if (k == -d ||
                        (k != d && v[offset + k - 1] < v[offset + k + 1])
                    ) {
                        v[offset + k + 1]
                    } else {
                        v[offset + k - 1] + 1
                    }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    backtrack(trace, a, b, partner)
                    return
                }
            }
        }
        // Over the cap: leave the middle unmatched, i.e. "all changed".
    }

    private fun backtrack(
        trace: List<IntArray>,
        a: Window,
        b: Window,
        partner: IntArray,
    ) {
        var x = a.size
        var y = b.size
        for (d in trace.indices.reversed()) {
            val band = trace[d] // v before step d, for k in -(d+1)..(d+1)

            fun v(k: Int) = band[k + d + 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && v(k - 1) < v(k + 1))) k + 1 else k - 1
            val prevX = v(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                x--
                y--
                partner[a.from + x] = b.from + y
            }
            if (d > 0) {
                x = prevX
                y = prevY
            }
        }
    }

    companion object {
        /** No partner: the line was deleted or changed. */
        const val NONE: Int = -1

        /** Edit-distance cap; see the class description. */
        const val DEFAULT_MAX_EDITS: Int = 2_000
    }
}
