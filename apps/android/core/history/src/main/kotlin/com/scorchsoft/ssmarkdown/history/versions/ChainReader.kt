package com.scorchsoft.ssmarkdown.history.versions

/**
 * Rebuilds version texts from a pack: a keyframe is read whole, an older
 * version is its newer neighbour with its backward delta applied.
 *
 * Nothing is trusted. A record must pass its CRC, a delta must fit its
 * base exactly, and the rebuilt text must match the SHA-256 stored when
 * the version was recorded. A version that fails is reported as
 * unreadable, never returned wrong.
 */
internal class ChainReader(
    private val pack: PackFile,
) {
    /**
     * The text of `branch[position]` (a branch is oldest first), or null
     * when its chain is damaged. Starts from the nearest keyframe at or
     * above it, so at most [KeyframeRule.MAX_CHAIN_LENGTH] deltas are read.
     */
    fun read(
        branch: List<StoredVersion>,
        position: Int,
    ): String? {
        val start = (position until branch.size).firstOrNull { branch[it].keyframe } ?: return null
        var text: String? = text(branch[start], newer = null, verify = start == position)
        for (i in start - 1 downTo position) {
            text = text?.let { text(branch[i], it, verify = i == position) }
        }
        return text
    }

    /**
     * Visits every version of [branch] from newest to oldest with its
     * text, or null for each one a damaged record made unreadable. After
     * damage the walk picks up again at the next keyframe below it, so
     * only the versions between that keyframe and the bad record are lost.
     */
    fun walk(
        branch: List<StoredVersion>,
        visit: (StoredVersion, String?) -> Unit,
    ) {
        var newer: String? = null
        for (i in branch.indices.reversed()) {
            val version = branch[i]
            newer = if (version.keyframe || newer != null) text(version, newer, verify = true) else null
            visit(version, newer)
        }
    }

    /** True when [version]'s own record is intact and, for a keyframe, its text verifies. */
    fun isIntactKeyframe(version: StoredVersion): Boolean =
        version.keyframe && text(version, newer = null, verify = true) != null

    private fun text(
        version: StoredVersion,
        newer: String?,
        verify: Boolean,
    ): String? {
        val raw = pack.readRaw(version.offset, version.recordBytes) ?: return null
        val textBound = TextHash.maxBytes(version.info.textLength)
        val record =
            if (version.keyframe) {
                PackRecord.decode(raw, textBound)?.takeIf { it.keyframe }
            } else {
                val deltaBound = DELTA_OVERHEAD * ((newer?.length ?: 0) + 1L) + textBound
                PackRecord
                    .decode(
                        raw,
                        deltaBound.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    )?.takeUnless { it.keyframe }
            }
        val text =
            when {
                record == null -> null
                record.keyframe -> TextHash.text(record.body)
                newer != null -> LineDelta.apply(newer, record.body)
                else -> null
            }
        return text?.takeIf { it.length == version.info.textLength && (!verify || matches(it, version)) }
    }

    private fun matches(
        text: String,
        version: StoredVersion,
    ): Boolean = TextHash.sha256Hex(text) == version.info.sha256Hex

    private companion object {
        /** Bytes per delta op header, bounding a delta by its base's size. */
        const val DELTA_OVERHEAD = 12L
    }
}
