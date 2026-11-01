package com.scorchsoft.ssmarkdown.history.versions

import java.io.File
import java.io.FileOutputStream

/**
 * Writes a new pack generation holding every version of [index] except
 * the removed ones.
 *
 * A branch with nothing removed is copied record for record. A branch
 * that lost versions is walked newest to oldest and re-encoded with
 * [ChainEncoder], so the surviving versions get fresh, short chains:
 * removing a version never leaves a delta pointing at something gone.
 * This is the "collapse a run into its newest member" rule of
 * docs/robustness.md §3 in its general form.
 */
internal class PackRewriter(
    private val index: VersionIndex,
    private val reader: ChainReader,
    private val target: File,
) {
    /**
     * The new pack's versions, sorted by branch and sequence, plus any
     * versions dropped because their chain was already damaged.
     */
    class Result(
        val versions: List<StoredVersion>,
        val lost: List<VersionInfo>,
    )

    private val versions = ArrayList<StoredVersion>()
    private val lost = ArrayList<VersionInfo>()
    private var position = 0L

    fun rewrite(remove: Set<VersionId>): Result {
        val oldPack = PackFile(File(target.parentFile, index.packName))
        FileOutputStream(target).use { out ->
            for (branchId in index.branches.keys.sorted()) {
                val branch = index.branch(branchId)
                val copied = branch.none { it.id in remove } && copyVerbatim(branch, oldPack, out)
                if (!copied) reencode(branch, remove, out)
            }
            out.flush()
            out.fd.sync()
        }
        versions.sortWith(compareBy({ it.info.branch }, { it.id.seq }))
        return Result(versions, lost)
    }

    /** Copies [branch]'s records unchanged; false (having written nothing) if any cannot be read. */
    private fun copyVerbatim(
        branch: List<StoredVersion>,
        pack: PackFile,
        out: FileOutputStream,
    ): Boolean {
        val raws = branch.map { pack.readRaw(it.offset, it.recordBytes) ?: return false }
        branch.forEachIndexed { i, version ->
            out.write(raws[i])
            versions += version.copy(offset = position)
            position += raws[i].size
        }
        return true
    }

    private fun reencode(
        branch: List<StoredVersion>,
        remove: Set<VersionId>,
        out: FileOutputStream,
    ) {
        val encoder = ChainEncoder()
        var newerSlot = -1
        reader.walk(branch) { version, text ->
            when {
                version.id in remove -> {
                    Unit
                }

                text == null -> {
                    lost += version.info
                }

                else -> {
                    val record = encoder.next(text)
                    if (newerSlot >= 0 && record.newerChanges != null) {
                        val newer = versions[newerSlot]
                        versions[newerSlot] = newer.copy(info = newer.info.copy(changes = record.newerChanges))
                    }
                    out.write(record.bytes)
                    // Overwritten by the next older survivor's diff; only the oldest keeps this,
                    // and it keeps its own summary only if nothing older than it was removed.
                    val changes = if (version == branch.first()) version.info.changes else LineChangeSummary.NONE
                    val info =
                        version.info.copy(
                            keyframe = record.keyframe,
                            storedBytes = record.bytes.size,
                            changes = changes,
                        )
                    versions += StoredVersion(info, position)
                    newerSlot = versions.lastIndex
                    position += record.bytes.size
                }
            }
        }
    }
}
