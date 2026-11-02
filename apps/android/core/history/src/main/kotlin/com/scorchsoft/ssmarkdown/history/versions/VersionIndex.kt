package com.scorchsoft.ssmarkdown.history.versions

/**
 * A version as the index holds it: its public [info] plus where its body
 * is in the pack.
 *
 * @property info the metadata History shows.
 * @property offset where its record starts in the pack.
 */
internal data class StoredVersion(
    val info: VersionInfo,
    val offset: Long,
) {
    val id: VersionId get() = info.id
    val keyframe: Boolean get() = info.keyframe
    val recordBytes: Int get() = info.storedBytes
}

/**
 * Everything about one document's history except the bodies: the file
 * that is replaced atomically to commit any change.
 *
 * @property uri the document this history belongs to.
 * @property nextSeq the sequence number the next version will get.
 * @property lastOpenedMillis wall time the document was last opened; drives "not opened in" clears.
 * @property packName the pack file (in the same directory) the offsets point into.
 * @property packLength bytes of the pack that are committed; anything after is an uncommitted append.
 * @property branches labels of the branches, by id; [VersionInfo.MAIN_BRANCH] is always present.
 * @property versions every version, sorted by branch and then by sequence number.
 */
internal data class VersionIndex(
    val uri: String,
    val nextSeq: Long,
    val lastOpenedMillis: Long,
    val packName: String,
    val packLength: Long,
    val branches: Map<Int, String>,
    val versions: List<StoredVersion>,
) {
    /** One branch's versions, oldest first. */
    fun branch(id: Int): List<StoredVersion> = versions.filter { it.info.branch == id }

    /** The newest version of [branch], or null when it has none. */
    fun head(branch: Int = VersionInfo.MAIN_BRANCH): StoredVersion? = versions.lastOrNull { it.info.branch == branch }

    /** Bytes of the pack still referenced by some version. */
    val liveBytes: Long get() = versions.sumOf { it.recordBytes.toLong() }

    /** Bytes of the pack no version references any more: superseded heads. */
    val garbageBytes: Long get() = packLength - liveBytes

    /** Replaces versions with the same id, keeping order. */
    fun replacing(updated: List<StoredVersion>): VersionIndex {
        val byId = updated.associateBy { it.id }
        return copy(versions = versions.map { byId[it.id] ?: it })
    }

    companion object {
        const val MAIN_LABEL: String = ""

        fun empty(uri: String): VersionIndex =
            VersionIndex(uri, 1, 0, packName(1), 0, mapOf(VersionInfo.MAIN_BRANCH to MAIN_LABEL), emptyList())

        /** Pack files are numbered by generation so a rewrite never overwrites the live one. */
        fun packName(generation: Long): String = "pack-$generation.ssp"

        /** The generation in a [packName], or null for any other file name. */
        fun generationOf(name: String): Long? = name.removePrefix("pack-").removeSuffix(".ssp").toLongOrNull()
    }
}
