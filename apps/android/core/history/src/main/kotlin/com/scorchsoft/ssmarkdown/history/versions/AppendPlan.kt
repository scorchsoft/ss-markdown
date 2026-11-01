package com.scorchsoft.ssmarkdown.history.versions

/**
 * The records one new version adds to a pack.
 *
 * The new text is always stored whole: it is the new head, and the
 * newest version of every branch is a keyframe so opening it is a single
 * read. The old head becomes a backward delta from the new one when
 * [KeyframeRule.canDemoteHead] allows, and otherwise stays a keyframe,
 * in which case its record is untouched and nothing new is written for it.
 *
 * @property records encoded records to append: the demoted head's delta (if any), then the new head.
 * @property demotedHead the old head's new index entry (offset still to be filled in), or null.
 * @property changes lines the new version added and removed relative to the old head.
 */
internal class AppendPlan private constructor(
    val records: List<ByteArray>,
    val demotedHead: StoredVersion?,
    val changes: LineChangeSummary,
) {
    companion object {
        @Suppress("ReturnCount") // First version, unreadable head and capped diff each end the plan early.
        fun of(
            index: VersionIndex,
            reader: ChainReader,
            draft: VersionDraft,
        ): AppendPlan {
            val headRecord = PackRecord(keyframe = true, body = TextHash.bytes(draft.text)).encode()
            val branch = index.branch(VersionInfo.MAIN_BRANCH)
            if (branch.isEmpty()) return AppendPlan(listOf(headRecord), null, LineChangeSummary.NONE)
            // A damaged head stays as it is (a keyframe) and nothing chains through it.
            val headText =
                reader.read(branch, branch.lastIndex)
                    ?: return AppendPlan(listOf(headRecord), null, LineChangeSummary.NONE)
            val delta = LineDelta.encode(base = draft.text, target = headText)
            val changes =
                if (delta != null) {
                    LineChangeSummary(added = delta.baseOnlyLines, removed = delta.targetOnlyLines)
                } else {
                    LineDiff.summarize(headText, draft.text)
                }
            val deltaRecord = delta?.let { PackRecord(keyframe = false, body = it.bytes).encode() }
            if (deltaRecord == null || !KeyframeRule.canDemoteHead(branch, deltaRecord.size)) {
                return AppendPlan(listOf(headRecord), null, changes)
            }
            val head = branch.last()
            val demoted = head.copy(info = head.info.copy(keyframe = false, storedBytes = deltaRecord.size))
            return AppendPlan(listOf(deltaRecord, headRecord), demoted, changes)
        }
    }
}
