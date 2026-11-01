package com.scorchsoft.ssmarkdown.history.versions

import com.scorchsoft.ssmarkdown.history.journal.AtomicFile
import java.io.File
import java.io.IOException

/** What loading a document's history directory found. */
internal sealed interface HistoryLoad {
    data class Loaded(
        val history: DocumentHistory,
    ) : HistoryLoad

    data object Missing : HistoryLoad

    data class Unsupported(
        val formatVersion: Int,
    ) : HistoryLoad

    data object Corrupt : HistoryLoad
}

/**
 * One document's history on disk: `index.ssv` plus the pack it names,
 * in a directory of their own.
 *
 * Every change is two steps: append bodies to the pack and sync, then
 * replace the index with [AtomicFile]. The index rename is the commit. A
 * crash before it leaves the old index, which ignores the new pack bytes;
 * a crash after it leaves the new one, whose bodies are already synced.
 * Rewrites (pruning, compaction) write a whole new pack generation and
 * delete the old one only after the index points at the new one.
 */
@Suppress("TooManyFunctions") // Every operation on one history's two files, each a few lines.
internal class DocumentHistory private constructor(
    val directory: File,
    index: VersionIndex,
) {
    var index: VersionIndex = index
        private set

    private val pack: PackFile get() = PackFile(File(directory, index.packName))

    private val reader: ChainReader get() = ChainReader(pack)

    val key: DocumentKey get() = DocumentKey(index.uri)

    /** Bytes on disk: the index and the pack, garbage included. */
    fun diskBytes(): Long = File(directory, INDEX_FILE).length() + File(directory, index.packName).length()

    fun usage(): FileUsage =
        FileUsage(
            key,
            diskBytes(),
            index.versions.size,
            index.versions.count { it.info.pinned },
            index.lastOpenedMillis,
        )

    /** The verified text of [id]. */
    fun read(id: VersionId): ReadResult {
        val stored = index.versions.firstOrNull { it.id == id } ?: return ReadResult.NotFound
        val branch = index.branch(stored.info.branch)
        val position = branch.indexOf(stored)
        val text = reader.read(branch, position)
        return if (text !=
            null
        ) {
            ReadResult.Text(text, stored.info)
        } else {
            ReadResult.Corrupt(id, fallback(branch, position))
        }
    }

    /** The intact keyframe nearest to `branch[position]`, newer or older. */
    private fun fallback(
        branch: List<StoredVersion>,
        position: Int,
    ): VersionId? =
        branch.indices
            .filter { it != position && branch[it].keyframe }
            .sortedBy { kotlin.math.abs(it - position) }
            .firstOrNull { reader.isIntactKeyframe(branch[it]) }
            ?.let { branch[it].id }

    /** Visits [branchId]'s versions newest first with their texts (null when damaged). */
    fun walk(
        branchId: Int,
        visit: (StoredVersion, String?) -> Unit,
    ) {
        reader.walk(index.branch(branchId), visit)
    }

    /** True while the index file exists: false once [deleteAll] has run. */
    val exists: Boolean get() = File(directory, INDEX_FILE).isFile

    /** Records [draft] as the new head of the main branch. Throws [IOException]. */
    fun append(draft: VersionDraft): RecordResult {
        val head = index.head()
        val sha = TextHash.sha256Hex(draft.text)
        if (head != null && head.info.sha256Hex == sha) {
            if (draft.kind == VersionKind.Opened) commit(index.copy(lastOpenedMillis = draft.wallTimeMillis))
            return RecordResult.Unchanged(head.id)
        }
        val plan = AppendPlan.of(index, reader, draft)
        val offsets = pack.append(index.packLength, plan.records)
        val info =
            VersionInfo(
                id = VersionId(index.nextSeq),
                branch = VersionInfo.MAIN_BRANCH,
                wallTimeMillis = draft.wallTimeMillis,
                kind = draft.kind,
                label = draft.label,
                pinned = false,
                parent = draft.parent ?: head?.id,
                changes = plan.changes,
                textLength = draft.text.length,
                sha256Hex = sha,
                storedBytes = plan.records.last().size,
                keyframe = true,
            )
        val demoted = plan.demotedHead?.let { listOf(it.copy(offset = offsets[0])) }.orEmpty()
        val opened = if (draft.kind == VersionKind.Opened) draft.wallTimeMillis else index.lastOpenedMillis
        val base = index.replacing(demoted)
        commit(
            base.copy(
                nextSeq = index.nextSeq + 1,
                lastOpenedMillis = opened,
                packLength = offsets.last() + plan.records.last().size,
                versions = base.versions + StoredVersion(info, offsets.last()),
            ),
        )
        return RecordResult.Recorded(info)
    }

    /**
     * True when superseded heads take more of the pack than live
     * versions do. Compacting then keeps disk use within twice the live
     * size while copying the pack only every so often.
     */
    val needsCompaction: Boolean get() = index.garbageBytes > index.liveBytes

    /** Applies [change] to [id]'s metadata. Throws [IOException]. */
    fun update(
        id: VersionId,
        change: (VersionInfo) -> VersionInfo,
    ): UpdateResult {
        val stored = index.versions.firstOrNull { it.id == id } ?: return UpdateResult.NotFound
        val updated = stored.copy(info = change(stored.info))
        commit(index.replacing(listOf(updated)))
        return UpdateResult.Updated(updated.info)
    }

    /** Sets when the document was last opened. Throws [IOException]. */
    fun touch(nowMillis: Long) {
        commit(index.copy(lastOpenedMillis = nowMillis))
    }

    /**
     * Removes [remove] and rewrites the pack, re-encoding only the
     * branches that changed. Versions found unreadable on the way are
     * dropped too and reported as [Rewrite.lost]. Throws [IOException].
     */
    fun rewrite(remove: Set<VersionId>): Rewrite {
        val before = diskBytes()
        val removed = index.versions.filter { it.id in remove }.map { it.info }
        val oldPack = File(directory, index.packName)
        val generation = (VersionIndex.generationOf(index.packName) ?: 0) + 1
        val newPack = File(directory, VersionIndex.packName(generation))
        val rewritten = PackRewriter(index, reader, newPack).rewrite(remove)
        if (rewritten.versions.isEmpty()) {
            deleteAll()
            return Rewrite(removed, rewritten.lost, before)
        }
        commit(index.copy(packName = newPack.name, packLength = newPack.length(), versions = rewritten.versions))
        if (oldPack != newPack) oldPack.delete()
        return Rewrite(removed, rewritten.lost, before - diskBytes())
    }

    /** Adds a whole new branch built from [versions] (oldest first). Throws [IOException]. */
    fun addBranch(
        label: String,
        versions: List<BranchVersion>,
    ): Int {
        val branchId = (index.branches.keys.maxOrNull() ?: 0) + 1
        val built = BranchBuilder(index.nextSeq, branchId).build(versions)
        val offsets = pack.append(index.packLength, built.records)
        val stored = built.infos.mapIndexed { i, info -> StoredVersion(info, offsets[i]) }.sortedBy { it.id.seq }
        commit(
            index.copy(
                nextSeq = index.nextSeq + versions.size,
                packLength = offsets.lastOrNull()?.let { it + built.records.last().size } ?: index.packLength,
                branches = index.branches + (branchId to label),
                versions = index.versions + stored,
            ),
        )
        return branchId
    }

    /** Deletes this history: the index first, so a crash midway leaves nothing half-readable. */
    fun deleteAll() {
        File(directory, INDEX_FILE).delete()
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
        index = index.copy(versions = emptyList(), packLength = 0)
    }

    /** Copies this history's files to [target] under a new [uri] ("Save as"). Throws [IOException]. */
    fun copyTo(
        target: File,
        uri: String,
    ) {
        target.mkdirs()
        File(directory, index.packName).copyTo(File(target, index.packName), overwrite = true)
        AtomicFile(File(target, INDEX_FILE)).write(IndexCodec.encode(index.copy(uri = uri)))
    }

    private fun commit(next: VersionIndex) {
        AtomicFile(File(directory, INDEX_FILE)).write(IndexCodec.encode(next))
        index = next
    }

    /**
     * Outcome of a [rewrite].
     *
     * @property removed versions removed because they were asked for.
     * @property lost versions dropped because their chain was already damaged.
     * @property freedBytes disk bytes saved.
     */
    class Rewrite(
        val removed: List<VersionInfo>,
        val lost: List<VersionInfo>,
        val freedBytes: Long,
    )

    companion object {
        const val INDEX_FILE: String = "index.ssv"

        /** A fresh, empty history for [uri] in [directory]; nothing is written until the first append. */
        fun create(
            directory: File,
            uri: String,
        ): DocumentHistory = DocumentHistory(directory, VersionIndex.empty(uri))

        /**
         * Loads [directory]. A readable index also clears out leftovers
         * from interrupted writes: `.new` files and packs it does not name.
         * An unsupported or damaged index leaves everything untouched.
         */
        @Suppress("ReturnCount") // Missing, unreadable, then decoded: three distinct outcomes.
        fun load(directory: File): HistoryLoad {
            val file = File(directory, INDEX_FILE)
            if (!file.isFile) return HistoryLoad.Missing
            val bytes =
                try {
                    file.readBytes()
                } catch (ignored: IOException) {
                    return HistoryLoad.Corrupt
                }
            return when (val read = IndexCodec.decode(bytes)) {
                is IndexRead.Ok -> {
                    directory
                        .listFiles()
                        ?.filter { it.name != INDEX_FILE && it.name != read.index.packName }
                        ?.forEach { it.delete() }
                    HistoryLoad.Loaded(DocumentHistory(directory, read.index))
                }

                is IndexRead.Unsupported -> {
                    HistoryLoad.Unsupported(read.formatVersion)
                }

                IndexRead.Corrupt -> {
                    HistoryLoad.Corrupt
                }
            }
        }
    }
}

/**
 * A version about to be recorded.
 *
 * @property text its full text.
 * @property kind why it is recorded.
 * @property label an optional name.
 * @property parent its parent in the graph; null means the current head.
 * @property wallTimeMillis the clock reading, for display.
 */
internal class VersionDraft(
    val text: String,
    val kind: VersionKind,
    val label: String?,
    val parent: VersionId?,
    val wallTimeMillis: Long,
)
