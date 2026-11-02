package com.scorchsoft.ssmarkdown.history.versions

/**
 * When to store a whole text instead of a delta: Mercurial's rule from
 * `mercurial/revlogutils/deltas.py` (GPL; the rule, not the code), as
 * docs/robustness.md §3 sets out. A keyframe is stored when any of:
 *
 * - the delta is bigger than the text;
 * - rebuilding the version would read more than [LIMIT_DELTA2TEXT]× its
 *   text length in deltas;
 * - the chain of deltas would reach [MAX_CHAIN_LENGTH] (git's default
 *   pack depth).
 *
 * Together they bound restore time and how many versions one damaged
 * delta can take with it.
 */
internal object KeyframeRule {
    const val LIMIT_DELTA2TEXT: Long = 2
    const val MAX_CHAIN_LENGTH: Int = 50

    /** True when a version with these numbers must be a keyframe. */
    fun needsKeyframe(
        deltaBytes: Int,
        textLength: Int,
        chainLength: Int,
        chainPayload: Long,
    ): Boolean =
        deltaBytes > textLength ||
            chainLength >= MAX_CHAIN_LENGTH ||
            chainPayload > LIMIT_DELTA2TEXT * textLength

    /**
     * Whether the current head of [branch] (oldest first) may become a
     * delta of [deltaBytes]. Turning the head into a delta lengthens the
     * chain of every delta below it down to the next keyframe, so the
     * rule is checked for each of them, not just for the head.
     */
    @Suppress("ReturnCount") // The head's own check, then each deeper delta's.
    fun canDemoteHead(
        branch: List<StoredVersion>,
        deltaBytes: Int,
    ): Boolean {
        val head = branch.last()
        if (needsKeyframe(deltaBytes, head.info.textLength, 1, deltaBytes.toLong())) return false
        var chainLength = 1
        var payload = deltaBytes.toLong()
        for (i in branch.size - 2 downTo 0) {
            val version = branch[i]
            if (version.keyframe) break
            chainLength++
            payload += version.recordBytes
            if (needsKeyframe(version.recordBytes, version.info.textLength, chainLength, payload)) return false
        }
        return true
    }
}

/**
 * Encodes a branch from newest to oldest, deciding keyframe or delta for
 * each version as it goes. Used when a branch is rewritten (pruning,
 * import), where every chain is rebuilt from scratch.
 */
internal class ChainEncoder {
    private var newerText: String? = null
    private var chainLength = 0
    private var chainPayload = 0L

    /** The encoded record for [text], the next older version after the last one encoded. */
    fun next(text: String): EncodedRecord {
        val newer = newerText
        newerText = text
        val delta = newer?.let { LineDelta.encode(base = it, target = text) }
        val newerChanges =
            when {
                delta != null -> LineChangeSummary(added = delta.baseOnlyLines, removed = delta.targetOnlyLines)
                newer != null -> LineDiff.summarize(text, newer)
                else -> null
            }
        if (delta != null) {
            val bytes = PackRecord(keyframe = false, body = delta.bytes).encode()
            if (!KeyframeRule.needsKeyframe(bytes.size, text.length, chainLength + 1, chainPayload + bytes.size)) {
                chainLength++
                chainPayload += bytes.size
                return EncodedRecord(bytes, keyframe = false, newerChanges)
            }
        }
        chainLength = 0
        chainPayload = 0
        return EncodedRecord(
            PackRecord(keyframe = true, body = TextHash.bytes(text)).encode(),
            keyframe = true,
            newerChanges,
        )
    }
}

/**
 * A record ready to append.
 *
 * @property bytes the encoded record.
 * @property keyframe whether it holds a whole text.
 * @property newerChanges lines the previously encoded (newer) version added and removed relative
 *   to this one, or null for the first version encoded.
 */
internal class EncodedRecord(
    val bytes: ByteArray,
    val keyframe: Boolean,
    val newerChanges: LineChangeSummary?,
)
