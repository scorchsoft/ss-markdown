package com.scorchsoft.ssmarkdown.history

import com.scorchsoft.ssmarkdown.history.versions.LineChangeSummary
import com.scorchsoft.ssmarkdown.history.versions.VersionId
import com.scorchsoft.ssmarkdown.history.versions.VersionInfo
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class HistoryTimelineTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 100 * day + 12 * hour

    private fun v(
        seq: Long,
        at: Long,
        kind: VersionKind = VersionKind.Autosaved,
        pinned: Boolean = false,
    ) = VersionInfo(VersionId(seq), 0, at, kind, null, pinned, null, LineChangeSummary.NONE, 0, "", 0, true)

    @Test
    fun `days are Today, Yesterday, then dates, newest first`() {
        val days = HistoryTimeline.group(listOf(v(1, now - 3 * day), v(2, now - day), v(3, now)), now, ZoneOffset.UTC)
        assertEquals(DayLabel.Today, days[0].label)
        assertEquals(DayLabel.Yesterday, days[1].label)
        assertTrue(days[2].label is DayLabel.Date)
    }

    @Test
    fun `older autosaves in one hour collapse, pins and saves never do`() {
        val base = now - day
        val versions =
            listOf(
                v(1, base),
                v(2, base + 60_000),
                v(3, base + 120_000),
                v(4, base + 180_000, VersionKind.Saved),
                v(5, base + 240_000, pinned = true),
            )
        val rows = HistoryTimeline.group(versions, now, ZoneOffset.UTC).single().rows
        assertEquals(3, rows.size)
        assertTrue(rows.last() is TimelineRow.Collapsed)
        val key = (rows.last() as TimelineRow.Collapsed).key
        assertEquals(
            5,
            HistoryTimeline
                .group(versions, now, ZoneOffset.UTC, setOf(key))
                .single()
                .rows.size,
        )
    }

    @Test
    fun `today is never collapsed`() {
        val versions = (1L..5L).map { v(it, now - it * 60_000) }
        assertEquals(
            5,
            HistoryTimeline
                .group(versions, now, ZoneOffset.UTC)
                .single()
                .rows.size,
        )
    }
}
