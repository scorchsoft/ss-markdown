package com.scorchsoft.ssmarkdown.history.versions

/**
 * A version to put on a new branch (an import). Its text is read on
 * demand, so a large import never holds every text in memory at once.
 *
 * @property template its metadata; id, branch, storage fields are filled in by [BranchBuilder].
 * @property text reads its text; must return the text whose hash is in [template].
 */
internal class BranchVersion(
    val template: VersionInfo,
    val text: () -> String,
)

/**
 * Encodes a whole branch from its versions, newest first, with fresh
 * sequence numbers starting at [firstSeq] in oldest-first order.
 */
internal class BranchBuilder(
    private val firstSeq: Long,
    private val branchId: Int,
) {
    /** Records in the order to append them, and the matching metadata. */
    class Built(
        val records: List<ByteArray>,
        val infos: List<VersionInfo>,
    )

    fun build(versions: List<BranchVersion>): Built {
        val encoder = ChainEncoder()
        val records = ArrayList<ByteArray>(versions.size)
        val infos = ArrayList<VersionInfo>(versions.size)
        for (i in versions.indices.reversed()) {
            val record = encoder.next(versions[i].text())
            records += record.bytes
            infos +=
                versions[i].template.copy(
                    id = VersionId(firstSeq + i),
                    branch = branchId,
                    storedBytes = record.bytes.size,
                    keyframe = record.keyframe,
                )
        }
        return Built(records, infos)
    }
}
