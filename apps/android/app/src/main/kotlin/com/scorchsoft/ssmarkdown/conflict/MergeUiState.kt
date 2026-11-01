package com.scorchsoft.ssmarkdown.conflict

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.files.merge.ConflictResolver
import com.scorchsoft.ssmarkdown.files.merge.MergeChunk
import com.scorchsoft.ssmarkdown.files.merge.MergeResult

/** How one overlap is settled (MergeResolve). */
sealed interface OverlapChoice {
    data object Theirs : OverlapChoice

    data object Mine : OverlapChoice

    /** Both, the file's lines first. */
    data object Both : OverlapChoice

    /** Typed by hand. */
    data class Edited(
        val text: String,
    ) : OverlapChoice
}

/**
 * One overlap: both sides changed the same lines differently.
 *
 * @property line where it sits in the merged text, 1-based.
 * @property section the heading it sits under, if any: "under “Q3”".
 */
@Immutable
data class Overlap(
    val line: Int,
    val section: String?,
    val theirs: String,
    val mine: String,
    val choice: OverlapChoice? = null,
)

/**
 * MergeResolve: the overlaps of a three-way merge, settled one at a
 * time. Nothing is written until the user chooses Done and then saves.
 *
 * @property cleanMerges changes that merged by themselves.
 * @property current the overlap on screen.
 */
@Immutable
data class MergeUiState(
    val fileName: String,
    val cleanMerges: Int,
    val overlaps: List<Overlap>,
    val current: Int = 0,
    val editing: Boolean = false,
    val historyOn: Boolean = true,
    val madeFromVersion: Long = 0,
) {
    /** Overlaps still undecided: "Done (1 left)". */
    val remaining: Int get() = overlaps.count { it.choice == null }

    /** Records [choice] for the current overlap and moves to the next undecided one. */
    fun choose(choice: OverlapChoice): MergeUiState {
        val updated = overlaps.mapIndexed { i, o -> if (i == current) o.copy(choice = choice) else o }
        val next = updated.indexOfFirst { it.choice == null }.takeIf { it >= 0 } ?: current
        return copy(overlaps = updated, current = next, editing = false)
    }

    /** The merged text, settling each overlap as chosen; undecided ones keep the file's lines. */
    fun mergedText(result: MergeResult): String {
        val choices = overlaps.map { it.choice }.iterator()
        return result.text(
            ConflictResolver { conflict ->
                when (val choice = if (choices.hasNext()) choices.next() else null) {
                    OverlapChoice.Mine -> conflict.ours
                    OverlapChoice.Both -> conflict.theirs + conflict.ours
                    is OverlapChoice.Edited -> listOf(choice.text.let { if (it.endsWith("\n")) it else "$it\n" })
                    OverlapChoice.Theirs, null -> conflict.theirs
                }
            },
        )
    }

    companion object {
        /** The overlaps of [result], located in the merged text. */
        fun of(
            fileName: String,
            result: MergeResult,
        ): MergeUiState {
            var line = 1
            var section: String? = null
            val overlaps = ArrayList<Overlap>()
            for (chunk in result.chunks) {
                when (chunk) {
                    is MergeChunk.Resolved -> {
                        section = lastHeading(chunk.lines) ?: section
                        line += chunk.lines.size
                    }

                    is MergeChunk.Conflict -> {
                        overlaps += Overlap(line, section, chunk.theirs.joinToString(""), chunk.ours.joinToString(""))
                        line += chunk.theirs.size
                    }
                }
            }
            return MergeUiState(fileName, result.cleanChangeCount, overlaps)
        }

        /** The last ATX heading's text among [lines], or null. */
        private fun lastHeading(lines: List<String>): String? =
            lines
                .lastOrNull { it.trimStart().startsWith("#") }
                ?.trim()
                ?.trimStart('#')
                ?.trim()
    }
}
