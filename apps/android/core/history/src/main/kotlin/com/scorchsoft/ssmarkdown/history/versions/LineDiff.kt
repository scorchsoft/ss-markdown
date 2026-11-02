package com.scorchsoft.ssmarkdown.history.versions

/** How a line of a diff relates the two texts. */
public enum class DiffLineType {
    /** In both texts; shown for context around a change. */
    Context,

    /** Only in the old text. */
    Removed,

    /** Only in the new text. */
    Added,
}

/**
 * One line of a diff as the History and Compare screens show it.
 *
 * @property type whether the line is context, removed or added.
 * @property text the line, including its terminator if it had one.
 * @property oldNumber its 1-based line number in the old text, or null for an added line.
 * @property newNumber its 1-based line number in the new text, or null for a removed line.
 */
public data class DiffLine(
    public val type: DiffLineType,
    public val text: String,
    public val oldNumber: Int?,
    public val newNumber: Int?,
)

/**
 * A run of changes with context around it, as in a unified diff.
 *
 * @property oldStart 0-based first old line covered.
 * @property oldCount old lines covered (context plus removed).
 * @property newStart 0-based first new line covered.
 * @property newCount new lines covered (context plus added).
 * @property lines the lines, in display order: removals before additions within each change.
 */
public data class DiffHunk(
    public val oldStart: Int,
    public val oldCount: Int,
    public val newStart: Int,
    public val newCount: Int,
    public val lines: List<DiffLine>,
)

/**
 * Lines added and removed between two versions: the "+12 −3 lines" under
 * each History entry.
 *
 * @property added lines only in the newer text.
 * @property removed lines only in the older text.
 */
public data class LineChangeSummary(
    public val added: Int,
    public val removed: Int,
) {
    /** Summaries for well-known cases. */
    public companion object {
        /** No change, or nothing to compare with. */
        public val NONE: LineChangeSummary = LineChangeSummary(0, 0)
    }
}

/**
 * A line diff of two texts, for the History and Compare screens.
 *
 * It is the same Myers diff that builds history deltas, with the same
 * caps. When a cap is hit (two unrelated 50 MB files) the result is
 * still correct, just coarse: everything between the common prefix and
 * suffix is shown as one change, and [capped] says so.
 *
 * @property hunks the changes with context.
 * @property summary lines added and removed in total.
 * @property capped true when the budget ran out and the diff is the coarse fallback.
 */
public class LineDiff private constructor(
    public val hunks: List<DiffHunk>,
    public val summary: LineChangeSummary,
    public val capped: Boolean,
) {
    /** Entry points. */
    public companion object {
        /** Lines of context kept around each change by default, as in `diff -u`. */
        public const val DEFAULT_CONTEXT: Int = 3

        /** Diffs [old] against [new] with [context] lines of context around each change. */
        public fun compute(
            old: String,
            new: String,
            context: Int = DEFAULT_CONTEXT,
        ): LineDiff {
            require(context >= 0) { "context must not be negative: $context" }
            val oldLines = Lines.of(old)
            val newLines = Lines.of(new)
            val (regions, capped) = regions(oldLines, newLines)
            val summary =
                LineChangeSummary(
                    added = regions.sumOf { it.bEnd - it.bStart },
                    removed = regions.sumOf { it.aEnd - it.aStart },
                )
            return LineDiff(HunkBuilder(oldLines, newLines, context).build(regions), summary, capped)
        }

        /** Only the "+n −m" counts, without building hunks. */
        public fun summarize(
            old: String,
            new: String,
        ): LineChangeSummary {
            val (regions, _) = regions(Lines.of(old), Lines.of(new))
            return LineChangeSummary(regions.sumOf { it.bEnd - it.bStart }, regions.sumOf { it.aEnd - it.aStart })
        }

        private fun regions(
            old: Lines,
            new: Lines,
        ): Pair<List<EditRegion>, Boolean> {
            val interner = LineInterner()
            val a = interner.intern(old)
            val b = interner.intern(new)
            MyersDiff.diff(a, b)?.let { return it to false }
            return coarse(a, b) to true
        }

        /** Everything between the common prefix and suffix, as one change. */
        private fun coarse(
            a: IntArray,
            b: IntArray,
        ): List<EditRegion> {
            var prefix = 0
            while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
            var suffix = 0
            while (suffix < a.size - prefix && suffix < b.size - prefix &&
                a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
            ) {
                suffix++
            }
            return listOf(EditRegion(prefix, a.size - suffix, prefix, b.size - suffix))
        }
    }
}

/** Groups edit regions into hunks, merging changes whose context would overlap. */
private class HunkBuilder(
    private val old: Lines,
    private val new: Lines,
    private val context: Int,
) {
    fun build(regions: List<EditRegion>): List<DiffHunk> {
        val hunks = ArrayList<DiffHunk>()
        var group = ArrayList<EditRegion>()
        for (region in regions) {
            val last = group.lastOrNull()
            if (last != null && region.aStart - last.aEnd > 2 * context) {
                hunks += hunk(group)
                group = ArrayList()
            }
            group += region
        }
        if (group.isNotEmpty()) hunks += hunk(group)
        return hunks
    }

    private fun hunk(group: List<EditRegion>): DiffHunk {
        val first = group.first()
        val oldStart = maxOf(0, first.aStart - context)
        val newStart = first.bStart - (first.aStart - oldStart)
        val lines = ArrayList<DiffLine>()
        var a = oldStart
        var b = newStart
        for (region in group) {
            while (a < region.aStart) lines += contextLine(a++, b++)
            for (i in region.aStart until region.aEnd) lines += DiffLine(DiffLineType.Removed, old[i], i + 1, null)
            for (j in region.bStart until region.bEnd) lines += DiffLine(DiffLineType.Added, new[j], null, j + 1)
            a = region.aEnd
            b = region.bEnd
        }
        val oldEnd = minOf(old.count, a + context)
        while (a < oldEnd) lines += contextLine(a++, b++)
        return DiffHunk(oldStart, a - oldStart, newStart, b - newStart, lines)
    }

    private fun contextLine(
        a: Int,
        b: Int,
    ) = DiffLine(DiffLineType.Context, old[a], a + 1, b + 1)
}
