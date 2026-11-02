package com.scorchsoft.ssmarkdown.conflict

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.history.versions.DiffLineType
import com.scorchsoft.ssmarkdown.history.versions.LineDiff

/** ComparePhone's toggle: the merged diff, or either side whole. */
enum class CompareView { Changes, Theirs, Mine }

/** Where a compare came from, which decides its footer buttons. */
enum class CompareOrigin {
    /** The conflict sheet: Keep the file's version · Merge my edits. */
    Conflict,

    /** The recovery banner: the file on disk against the restored edits. */
    Recovery,

    /** History: a version against the current text. */
    History,
}

/**
 * One row of the single scrolling diff.
 *
 * @property collapsed for a run of unchanged lines: "⋯ 18 unchanged lines".
 */
@Immutable
data class CompareRow(
    val type: DiffLineType,
    val text: String,
    val theirsLine: Int?,
    val mineLine: Int?,
    val collapsed: Int = 0,
)

/**
 * The Compare screen: on a phone, one scrolling diff with a Theirs / Mine
 * toggle (ux-spec "File changed outside the app"). Removed text is grey
 * with a strike-through, added text is info blue: never red.
 */
@Immutable
data class CompareUiState(
    val fileName: String,
    val origin: CompareOrigin,
    val theirs: String,
    val mine: String,
    val rows: List<CompareRow>,
    val differences: Int,
    val view: CompareView = CompareView.Changes,
) {
    companion object {
        /** Builds the diff of [theirs] (old) against [mine] (new), with 3 lines of context. */
        fun of(
            fileName: String,
            origin: CompareOrigin,
            theirs: String,
            mine: String,
        ): CompareUiState {
            val diff = LineDiff.compute(theirs, mine)
            val rows = ArrayList<CompareRow>()
            var lastOld = 0
            for (hunk in diff.hunks) {
                val gap = hunk.oldStart - lastOld
                if (gap > 0) rows += CompareRow(DiffLineType.Context, "", null, null, collapsed = gap)
                hunk.lines.forEach { rows += CompareRow(it.type, it.text, it.oldNumber, it.newNumber) }
                lastOld = hunk.oldStart + hunk.oldCount
            }
            return CompareUiState(fileName, origin, theirs, mine, rows, diff.hunks.size)
        }
    }
}
