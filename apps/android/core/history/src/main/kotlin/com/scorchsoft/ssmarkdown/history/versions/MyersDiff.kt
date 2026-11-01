package com.scorchsoft.ssmarkdown.history.versions

/**
 * One changed region: `a[aStart until aEnd]` became `b[bStart until bEnd]`.
 * Either side may be empty (a pure insert or delete), never both.
 */
internal data class EditRegion(
    val aStart: Int,
    val aEnd: Int,
    val bStart: Int,
    val bEnd: Int,
)

/**
 * How much work one diff may do before giving up. A diff that hits a cap
 * returns null; history then stores a keyframe and a viewer shows the
 * whole span as changed, so a pathological pair of texts costs bounded
 * time instead of an ANR (docs/robustness.md §3).
 *
 * @property maxEdits the most inserted plus deleted items one diff may report.
 * @property maxWork the most inner-loop steps (a deterministic stand-in for a time cap).
 */
internal data class DiffBudget(
    val maxEdits: Int = DEFAULT_MAX_EDITS,
    val maxWork: Long = DEFAULT_MAX_WORK,
) {
    companion object {
        const val DEFAULT_MAX_EDITS: Int = 20_000
        const val DEFAULT_MAX_WORK: Long = 40_000_000
    }
}

/**
 * Linear-space Myers diff ("An O(ND) Difference Algorithm and Its
 * Variations", Myers 1986, §4b), the algorithm behind GNU diff and git.
 *
 * Each step finds the middle snake of the remaining box and recurses on
 * both halves, so memory is O(N + M) however large the files are. The
 * common prefix and suffix of every box are stripped first, which makes
 * the usual case (a few edits in a long document) close to linear.
 */
internal class MyersDiff private constructor(
    private val a: IntArray,
    private val b: IntArray,
    private val budget: DiffBudget,
) {
    private var work = 0L
    private var edits = 0
    private val out = ArrayList<EditRegion>()

    private class Capped : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun run(): List<EditRegion>? =
        try {
            diff(0, a.size, 0, b.size)
            merged()
        } catch (ignored: Capped) {
            null
        }

    private fun diff(
        aLo0: Int,
        aHi0: Int,
        bLo0: Int,
        bHi0: Int,
    ) {
        var aLo = aLo0
        var aHi = aHi0
        var bLo = bLo0
        var bHi = bHi0
        while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
            aLo++
            bLo++
        }
        while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) {
            aHi--
            bHi--
        }
        if (aLo == aHi || bLo == bHi) {
            if (aLo < aHi || bLo < bHi) emit(EditRegion(aLo, aHi, bLo, bHi))
            return
        }
        val snake = MiddleSnake(aLo, aHi, bLo, bHi).find()
        diff(aLo, snake.aStart, bLo, snake.bStart)
        diff(snake.aEnd, aHi, snake.bEnd, bHi)
    }

    private fun emit(region: EditRegion) {
        edits += (region.aEnd - region.aStart) + (region.bEnd - region.bStart)
        if (edits > budget.maxEdits) throw Capped()
        out += region
    }

    private fun charge(steps: Int) {
        work += steps
        if (work > budget.maxWork) throw Capped()
    }

    /** Joins regions that touch, so callers see one hunk per changed run. */
    private fun merged(): List<EditRegion> {
        val result = ArrayList<EditRegion>(out.size)
        for (region in out) {
            val last = result.lastOrNull()
            if (last != null && last.aEnd == region.aStart && last.bEnd == region.bStart) {
                result[result.size - 1] = last.copy(aEnd = region.aEnd, bEnd = region.bEnd)
            } else {
                result += region
            }
        }
        return result
    }

    /** The middle snake of one box; [find] returns it as a region of matching items, in absolute indices. */
    private inner class MiddleSnake(
        private val aLo: Int,
        aHi: Int,
        private val bLo: Int,
        bHi: Int,
    ) {
        private val n = aHi - aLo
        private val m = bHi - bLo
        private val delta = n - m
        private val odd = delta and 1 != 0
        private val max = (n + m + 1) / 2
        private val offset = max + 1
        private val forward = IntArray(2 * max + 3)
        private val backward = IntArray(2 * max + 3)

        fun find(): EditRegion {
            for (d in 0..max) {
                if (d > budget.maxEdits) throw Capped()
                charge(d + 1)
                forwardStep(d)?.let { return it }
                backwardStep(d)?.let { return it }
            }
            error("Myers: no middle snake in a ${n}x$m box") // Unreachable: d = max always overlaps.
        }

        private fun forwardStep(d: Int): EditRegion? {
            for (k in -d..d step 2) {
                var x =
                    if (k == -d || (k != d && forward[offset + k - 1] < forward[offset + k + 1])) {
                        forward[offset + k + 1]
                    } else {
                        forward[offset + k - 1] + 1
                    }
                var y = x - k
                val startX = x
                val startY = y
                while (x < n && y < m && a[aLo + x] == b[bLo + y]) {
                    x++
                    y++
                }
                charge(x - startX)
                forward[offset + k] = x
                if (odd && reaches(delta - k, d - 1, x, backward)) {
                    return EditRegion(aLo + startX, aLo + x, bLo + startY, bLo + y)
                }
            }
            return null
        }

        /** True when diagonal [k] of the other search (explored to depth [depth]) meets [x]: the paths overlap. */
        private fun reaches(
            k: Int,
            depth: Int,
            x: Int,
            other: IntArray,
        ): Boolean = k in -depth..depth && x + other[offset + k] >= n

        private fun backwardStep(d: Int): EditRegion? {
            for (k in -d..d step 2) {
                var x =
                    if (k == -d || (k != d && backward[offset + k - 1] < backward[offset + k + 1])) {
                        backward[offset + k + 1]
                    } else {
                        backward[offset + k - 1] + 1
                    }
                var y = x - k
                val startX = x
                val startY = y
                while (x < n && y < m && a[aLo + n - 1 - x] == b[bLo + m - 1 - y]) {
                    x++
                    y++
                }
                charge(x - startX)
                backward[offset + k] = x
                if (!odd && reaches(delta - k, d, x, forward)) {
                    return EditRegion(aLo + n - x, aLo + n - startX, bLo + m - y, bLo + m - startY)
                }
            }
            return null
        }
    }

    companion object {
        /**
         * The regions where [a] and [b] differ, in order and non-adjacent,
         * or null when the [budget] ran out first.
         */
        fun diff(
            a: IntArray,
            b: IntArray,
            budget: DiffBudget = DiffBudget(),
        ): List<EditRegion>? = MyersDiff(a, b, budget).run()
    }
}
