package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.merge.MergeResult
import com.scorchsoft.ssmarkdown.files.merge.ThreeWayMerge
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint

/**
 * The file changed on disk while this document had unsaved edits
 * (robustness §2a). Nothing is applied until the user chooses; autosave
 * pauses and every save returns a conflict.
 *
 * "Mine" is the live buffer, which stays editable, so it is passed in
 * when the sheet asks for a merge rather than frozen here.
 *
 * @property base the version the edits started from, from the base copy; null if that copy was
 *   lost, in which case only Compare, Keep the file and Use mine are offered.
 * @property theirs the file as it is now.
 * @property theirsFingerprint the fingerprint of [theirs]; resolving re-checks it.
 * @property recovered true when found while recovering a journal after a restart.
 */
class ConflictState(
    val base: String?,
    val theirs: String,
    val theirsFingerprint: BaseFingerprint,
    val recovered: Boolean,
) {
    /** The three-way merge of [mine] into [theirs], or null without a base. */
    fun merge(mine: CharSequence): MergeResult? = base?.let { ThreeWayMerge().merge(it, mine, theirs) }
}

/** How a conflict resolution went. */
sealed interface ResolveOutcome {
    /** Done; the document now reflects the choice. */
    data object Resolved : ResolveOutcome

    /** The file changed again in the meantime; the conflict now holds the newer version. */
    data object ChangedAgain : ResolveOutcome

    /** There was no conflict to resolve. */
    data object NoConflict : ResolveOutcome

    /**
     * The buffer was edited after the user chose, so the chosen text no
     * longer contains every edit; nothing changed. Review again (DS-7).
     */
    data object BufferChanged : ResolveOutcome

    /**
     * The disk could not be read; nothing changed.
     *
     * @property outcome what the check found.
     */
    data class Unavailable(
        val outcome: OutsideChange,
    ) : ResolveOutcome
}
