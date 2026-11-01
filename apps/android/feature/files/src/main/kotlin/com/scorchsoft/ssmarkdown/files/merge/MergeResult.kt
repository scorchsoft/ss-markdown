package com.scorchsoft.ssmarkdown.files.merge

/** One run of a three-way merge's output. */
sealed interface MergeChunk {
    /**
     * Text both sides agree on, or a change only one side made (or both
     * made identically).
     *
     * @property lines the merged lines, each with its own terminator.
     * @property changed true when this run came from an edit rather than untouched base text.
     */
    data class Resolved(
        val lines: List<String>,
        val changed: Boolean,
    ) : MergeChunk

    /**
     * Both sides changed the same region differently. Never resolved
     * automatically: the conflict sheet presents it (take theirs / take
     * mine / edit).
     *
     * @property base the region as it was.
     * @property ours the region in the unsaved buffer.
     * @property theirs the region as now on disk.
     */
    data class Conflict(
        val base: List<String>,
        val ours: List<String>,
        val theirs: List<String>,
    ) : MergeChunk
}

/** How the user settles one [MergeChunk.Conflict]. */
fun interface ConflictResolver {
    /** The lines to use in place of [conflict]. */
    fun resolve(conflict: MergeChunk.Conflict): List<String>

    /** The two one-tap answers. */
    companion object {
        /** Keep my edit. */
        val TAKE_OURS: ConflictResolver = ConflictResolver { it.ours }

        /** Keep the file's edit. */
        val TAKE_THEIRS: ConflictResolver = ConflictResolver { it.theirs }
    }
}

/**
 * A three-way merge's result: the merged runs in order.
 *
 * @property chunks the output, in document order.
 */
data class MergeResult(
    val chunks: List<MergeChunk>,
) {
    /** Overlapping changes the user must settle. */
    val conflictCount: Int get() = chunks.count { it is MergeChunk.Conflict }

    /** Changes that merged by themselves: the "3 merge cleanly" in the sheet's summary. */
    val cleanChangeCount: Int get() = chunks.count { it is MergeChunk.Resolved && it.changed }

    /** True when nothing overlaps. */
    val isClean: Boolean get() = conflictCount == 0

    /** The merged text, settling each conflict with [resolver]. */
    fun text(resolver: ConflictResolver): String =
        buildString {
            for (chunk in chunks) {
                val lines =
                    when (chunk) {
                        is MergeChunk.Resolved -> chunk.lines
                        is MergeChunk.Conflict -> resolver.resolve(chunk)
                    }
                lines.forEach(::append)
            }
        }

    /** The merged text when [isClean]; null if any conflict remains. */
    fun cleanText(): String? = if (isClean) text { error("unreachable: no conflicts") } else null
}
