package com.scorchsoft.ssmarkdown.history.versions

import java.io.IOException

/**
 * A limit that could not be met without breaking a protection (latest
 * version, last 7 days, pins). Retention stops and says so rather than
 * deleting more (docs/ux-spec.md, "Automatic limits").
 *
 * @property document the file, or null for the total limit.
 * @property limitBytes the limit.
 * @property usedBytes what is still used.
 */
public data class LimitShortfall(
    public val document: DocumentKey?,
    public val limitBytes: Long,
    public val usedBytes: Long,
)

/**
 * What one retention pass did.
 *
 * @property removals every removal, exactly as appended to the [CleanupLog].
 * @property shortfalls limits that could not be met.
 * @property failures documents whose history could not be rewritten; they are as they were.
 */
public data class RetentionReport(
    public val removals: List<CleanupEntry>,
    public val shortfalls: List<LimitShortfall>,
    public val failures: List<IOException>,
) {
    /** True when a storage limit (not just the schedule) removed something: the cue for the one-time notice. */
    public val limitThinningHappened: Boolean
        get() = removals.any { it.reason == CleanupReason.FileLimit || it.reason == CleanupReason.TotalLimit }
}

/**
 * One retention pass over every history: forget unused files (if
 * asked), thin by the schedule, then meet the per-file and total limits.
 *
 * Limits only ever remove [RetentionRules.limitCandidates], oldest first,
 * with files not opened for 90 days going first under the total limit.
 */
internal class RetentionRunner(
    private val directory: HistoryDirectory,
    private val pruner: Pruner,
    private val policy: HistoryPolicy,
    private val now: Long,
) {
    private val removals = ArrayList<CleanupEntry>()
    private val shortfalls = ArrayList<LimitShortfall>()
    private val failures = ArrayList<IOException>()

    fun run(): RetentionReport {
        for (history in directory.all()) {
            guarded {
                forgetIfUnused(history)
                if (history.exists) thin(history)
                val limit = policy.perFileLimit
                if (history.exists && limit is StorageLimit.Bytes) enforceFileLimit(history, limit.bytes)
            }
        }
        (policy.totalLimit as? StorageLimit.Bytes)?.let { guarded { enforceTotalLimit(it.bytes) } }
        return RetentionReport(removals, shortfalls, failures)
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            failures += e
        }
    }

    private fun forgetIfUnused(history: DocumentHistory) {
        val days = policy.forgetUnusedAfterDays ?: return
        if (now - history.index.lastOpenedMillis < days * RetentionRules.DAY) return
        val ids =
            history.index.versions
                .filter { !it.info.pinned }
                .map { it.id }
                .toSet()
        removals += pruner.remove(history, ids, CleanupReason.UnusedFile)
    }

    private fun thin(history: DocumentHistory) {
        val ids =
            history.index.branches.keys
                .flatMap { branch ->
                    RetentionRules.thin(history.index.branch(branch).map { it.info }, now)
                }.map { it.id }
                .toSet()
        if (ids.isNotEmpty()) removals += pruner.remove(history, ids, CleanupReason.Thinned)
    }

    private fun candidates(history: DocumentHistory): List<VersionInfo> =
        history.index.branches.keys
            .flatMap { RetentionRules.limitCandidates(history.index.branch(it).map { v -> v.info }, now) }
            .sortedWith(compareBy({ it.wallTimeMillis }, { it.id.seq }))

    private fun enforceFileLimit(
        history: DocumentHistory,
        limit: Long,
    ) {
        repeat(MAX_PASSES) {
            if (!history.exists || history.diskBytes() <= limit) return
            if (history.index.garbageBytes > 0) {
                removals += pruner.remove(history, emptySet(), CleanupReason.FileLimit, limit)
                return@repeat
            }
            val chosen = takeUntil(candidates(history), history.diskBytes() - limit) { it.storedBytes.toLong() }
            if (chosen.isEmpty()) {
                shortfalls += LimitShortfall(history.key, limit, history.diskBytes())
                return
            }
            removals += pruner.remove(history, chosen.map { it.id }.toSet(), CleanupReason.FileLimit, limit)
        }
    }

    private fun enforceTotalLimit(limit: Long) {
        repeat(MAX_PASSES) {
            val histories = directory.all()
            val used = histories.sumOf { it.diskBytes() }
            if (used <= limit) return
            val ranked =
                histories
                    .flatMap { history ->
                        val stale = now - history.index.lastOpenedMillis >= RetentionRules.STALE_FILE
                        candidates(history).map { Triple(history, it, stale) }
                    }.sortedWith(compareBy({ !it.third }, { it.second.wallTimeMillis }, { it.second.id.seq }))
            val chosen = takeUntil(ranked, used - limit) { it.second.storedBytes.toLong() }
            if (chosen.isEmpty()) {
                shortfalls += LimitShortfall(null, limit, used)
                return
            }
            chosen.groupBy({ it.first }, { it.second.id }).forEach { (history, ids) ->
                removals += pruner.remove(history, ids.toSet(), CleanupReason.TotalLimit, limit)
            }
        }
    }

    /** The shortest prefix of [items] whose [size]s add up to at least [excess]. */
    private fun <T> takeUntil(
        items: List<T>,
        excess: Long,
        size: (T) -> Long,
    ): List<T> {
        var total = 0L
        val out = ArrayList<T>()
        for (item in items) {
            if (total >= excess) break
            out += item
            total += size(item)
        }
        return out
    }

    private companion object {
        /** Re-measure after each rewrite; a few passes always suffice, the cap only guards against a loop. */
        const val MAX_PASSES = 16
    }
}
