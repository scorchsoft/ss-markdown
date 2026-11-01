package com.scorchsoft.ssmarkdown.history.versions

/**
 * The pure rules of retention: which versions the thinning schedule
 * drops, and which ones a storage limit may touch at all
 * (docs/robustness.md §3, "Retention").
 *
 * Buckets are aligned to fixed multiples of an hour, a day or a week of
 * wall time, so running the schedule twice removes nothing new: a
 * bucket's survivor stays its survivor. Wall time can jump (a clock
 * change); a version "from the future" is treated as brand new, so a
 * clock change can only make retention keep more.
 */
internal object RetentionRules {
    const val HOUR: Long = 3_600_000
    const val DAY: Long = 24 * HOUR
    const val WEEK: Long = 7 * DAY

    /** Everything younger than this is kept by the schedule. */
    const val KEEP_ALL: Long = DAY

    /** Hourly buckets up to this age, then daily. */
    const val HOURLY_UNTIL: Long = 7 * DAY

    /** Daily buckets up to this age, then weekly. */
    const val DAILY_UNTIL: Long = 90 * DAY

    /** Limits never touch anything younger than this. */
    const val LIMIT_PROTECTED: Long = 7 * DAY

    /** Files not opened for this long are thinned first when the total limit bites. */
    const val STALE_FILE: Long = 90 * DAY

    /**
     * The versions of one [branch] (oldest first) the schedule removes at
     * [now]: every one that is not the newest in its time bucket. The
     * branch's latest version and pinned versions are never chosen.
     */
    fun thin(
        branch: List<VersionInfo>,
        now: Long,
    ): List<VersionInfo> {
        val latest = branch.lastOrNull() ?: return emptyList()
        val survivors = HashMap<Pair<Long, Long>, VersionInfo>()
        val bucketed = branch.filter { it != latest && !it.pinned && age(it, now) >= KEEP_ALL }
        for (version in bucketed) {
            val key = bucketOf(version, now)
            val current = survivors[key]
            if (current == null || version.id.seq > current.id.seq) survivors[key] = version
        }
        return bucketed.filter { survivors[bucketOf(it, now)] != it }
    }

    /**
     * The versions of [branch] a storage limit may remove, oldest first:
     * not the latest, not pinned, not from the last 7 days.
     */
    fun limitCandidates(
        branch: List<VersionInfo>,
        now: Long,
    ): List<VersionInfo> {
        val latest = branch.lastOrNull() ?: return emptyList()
        return branch.filter { it != latest && !it.pinned && age(it, now) >= LIMIT_PROTECTED }
    }

    private fun age(
        version: VersionInfo,
        now: Long,
    ): Long = maxOf(0, now - version.wallTimeMillis)

    private fun bucketOf(
        version: VersionInfo,
        now: Long,
    ): Pair<Long, Long> {
        val age = age(version, now)
        val size =
            when {
                age < HOURLY_UNTIL -> HOUR
                age < DAILY_UNTIL -> DAY
                else -> WEEK
            }
        return size to Math.floorDiv(version.wallTimeMillis, size)
    }
}
