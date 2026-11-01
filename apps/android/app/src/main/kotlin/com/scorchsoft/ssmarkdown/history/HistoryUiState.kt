package com.scorchsoft.ssmarkdown.history

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.history.versions.DiffLine
import com.scorchsoft.ssmarkdown.history.versions.ExportedDocument
import com.scorchsoft.ssmarkdown.history.versions.VersionId
import com.scorchsoft.ssmarkdown.history.versions.VersionInfo
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A day heading in the timeline: Today, Yesterday, or a date. */
sealed interface DayLabel {
    data object Today : DayLabel

    data object Yesterday : DayLabel

    data class Date(
        val date: LocalDate,
    ) : DayLabel
}

/** One row of the timeline: a version, or a collapsed run of them ("Hourly · 6 versions"). */
sealed interface TimelineRow {
    /** A version. */
    data class Version(
        val info: VersionInfo,
    ) : TimelineRow

    /** Older thinned-out versions in one hour, collapsed until tapped. */
    data class Collapsed(
        val key: String,
        val versions: List<VersionInfo>,
    ) : TimelineRow
}

/** One day of the timeline. */
@Immutable
data class TimelineDay(
    val label: DayLabel,
    val rows: List<TimelineRow>,
)

/**
 * The version-history timeline, grouped by day, newest first. On days
 * before today, runs of unpinned autosaves and checkpoints within one
 * hour collapse into a single row (ux-spec "Version history").
 */
object HistoryTimeline {
    private val COLLAPSIBLE = setOf(VersionKind.Autosaved, VersionKind.Checkpoint)
    private const val MIN_RUN = 3

    /** Groups [versions] (any order) relative to [nowMillis] in [zone]. */
    fun group(
        versions: List<VersionInfo>,
        nowMillis: Long,
        zone: ZoneId,
        expanded: Set<String> = emptySet(),
    ): List<TimelineDay> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return versions
            .sortedByDescending { it.id.seq }
            .groupBy { Instant.ofEpochMilli(it.wallTimeMillis).atZone(zone).toLocalDate() }
            .map { (date, list) ->
                val label =
                    when (date) {
                        today -> DayLabel.Today
                        today.minusDays(1) -> DayLabel.Yesterday
                        else -> DayLabel.Date(date)
                    }
                TimelineDay(
                    label,
                    if (date ==
                        today
                    ) {
                        list.map(TimelineRow::Version)
                    } else {
                        collapse(list, zone, expanded)
                    },
                )
            }
    }

    private fun collapse(
        list: List<VersionInfo>,
        zone: ZoneId,
        expanded: Set<String>,
    ): List<TimelineRow> {
        val rows = ArrayList<TimelineRow>()
        var run = ArrayList<VersionInfo>()
        var runHour: Long? = null

        fun flush() {
            val key = "$runHour:${run.firstOrNull()?.id?.seq}"
            if (run.size >= MIN_RUN &&
                key !in expanded
            ) {
                rows += TimelineRow.Collapsed(key, run)
            } else {
                run.forEach {
                    rows +=
                        TimelineRow.Version(it)
                }
            }
            run = ArrayList()
        }
        for (v in list) {
            val hour = Instant.ofEpochMilli(v.wallTimeMillis).atZone(zone).toEpochSecond() / SECONDS_PER_HOUR
            val collapsible = v.kind in COLLAPSIBLE && !v.pinned
            if (!collapsible || hour != runHour) flush()
            if (collapsible) {
                runHour = hour
                run += v
            } else {
                runHour = null
                rows += TimelineRow.Version(v)
            }
        }
        flush()
        return rows
    }

    private const val SECONDS_PER_HOUR = 3_600L
}

/**
 * The History screen (the History board).
 *
 * @property expanded the entry showing its diff, Compare with now and Restore.
 * @property diff the expanded entry's diff snippet against the version before it.
 * @property pinning the version being pinned and named (long-press).
 * @property recording false shows "Version history is off · Turn on".
 * @property autosaveThisFile null means "uses your Settings default".
 * @property importChoices an imported export with no certain match: which document is this file's?
 */
@Immutable
data class HistoryUiState(
    val fileName: String = "",
    val versionCount: Int = 0,
    val bytes: Long = 0,
    val days: List<TimelineDay> = emptyList(),
    val expanded: VersionId? = null,
    val expandedRuns: Set<String> = emptySet(),
    val diff: List<DiffLine> = emptyList(),
    val pinning: VersionInfo? = null,
    val recording: Boolean = true,
    val autosaveThisFile: Boolean? = null,
    val unsavedSummary: Pair<Int, Int>? = null,
    val loading: Boolean = true,
    val unreadable: Boolean = false,
    val isDraft: Boolean = false,
    val importChoices: List<ExportedDocument> = emptyList(),
)
