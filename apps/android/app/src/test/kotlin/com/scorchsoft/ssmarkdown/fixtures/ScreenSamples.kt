package com.scorchsoft.ssmarkdown.fixtures

import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.find.FindMatch
import com.scorchsoft.ssmarkdown.find.FindUiState
import com.scorchsoft.ssmarkdown.folder.FolderEntryRow
import com.scorchsoft.ssmarkdown.folder.FolderUiState
import com.scorchsoft.ssmarkdown.history.HistoryTimeline
import com.scorchsoft.ssmarkdown.history.HistoryUiState
import com.scorchsoft.ssmarkdown.history.versions.LineChangeSummary
import com.scorchsoft.ssmarkdown.history.versions.VersionId
import com.scorchsoft.ssmarkdown.history.versions.VersionInfo
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.navigator.NavigatorUiState
import com.scorchsoft.ssmarkdown.navigator.OutlineRow
import com.scorchsoft.ssmarkdown.settings.RecentFile
import com.scorchsoft.ssmarkdown.share.ShareUiState
import java.time.ZoneOffset

/**
 * The surfaces the round-2 review had to render by hand (UX2-13): the
 * Navigator, the folder in both forms, Find, the section menu, History and
 * the share sheet, plus the folder states for each adaptive width.
 */
object ScreenSamples {
    private val rows =
        listOf(
            FolderEntryRow("d", "guides", 0, isDirectory = true, unsavedInside = 1),
            FolderEntryRow("a", "quarterly-planning-and-roadmap-notes.md", 1, false, dirty = true, current = true),
            FolderEntryRow("b", "release-notes.md", 1, false),
            FolderEntryRow("c", "meeting-2026-10-30.md", 0, false, changedOutside = true),
            FolderEntryRow("e", "todo.txt", 0, false, muted = true),
        )

    /** A folder with one unsaved file, docked or as a closed drawer depending on the width. */
    val folder = FolderUiState("product-docs", "t", rows, fileCount = 4, unsavedCount = 1)

    /** The current file has a long name, to show what a 360 dp header does with it. */
    val longName =
        Samples.unsaved.copy(title = "quarterly-planning-and-roadmap-notes.md", folder = folder)

    val drawer = longName.copy(folder = folder.copy(drawerOpen = true))

    val navigator =
        Samples.section.copy(
            navigator =
                NavigatorUiState(
                    outline =
                        listOf(
                            OutlineRow(0, 1, "Team handbook"),
                            OutlineRow(4, 2, "How we work"),
                            OutlineRow(9, 3, "Code review", current = true),
                            OutlineRow(20, 3, "Releases"),
                            OutlineRow(31, 2, "Security"),
                        ),
                ),
        )

    val find =
        Samples.unsaved.copy(
            find = FindUiState(query = "save", matches = List(12) { FindMatch(it * 10, it * 10 + 4) }, current = 2),
        )

    val sectionMenu =
        Samples.saved.copy(
            mode = ViewMode.Preview,
            overlay = EditorOverlay.SectionMenu(9, "Code review", 14, "Releases"),
        )

    val split600: EditorUiState = Samples.section.copy(mode = ViewMode.Split)

    val docked: EditorUiState = Samples.section.copy(folder = folder)

    val wideSplit: EditorUiState = docked.copy(mode = ViewMode.Split)

    private const val HOUR = 3_600_000L

    private fun version(
        seq: Long,
        at: Long,
        kind: VersionKind,
        pinned: Boolean = false,
    ) = VersionInfo(VersionId(seq), 0, at, kind, null, pinned, null, LineChangeSummary.NONE, 0, "", 0, true)

    /** History with today, yesterday and a pinned version. The render clock is at 120 000 ms. */
    val history: HistoryUiState
        get() {
            val now = 3 * 24 * HOUR
            val versions =
                listOf(
                    version(1, now - 2 * 24 * HOUR, VersionKind.Saved, pinned = true),
                    version(2, now - 24 * HOUR, VersionKind.Autosaved),
                    version(3, now - 2 * HOUR, VersionKind.Saved),
                    version(4, now - HOUR, VersionKind.Autosaved),
                )
            return HistoryUiState(
                fileName = "roadmap.md",
                versionCount = versions.size,
                bytes = 48_200,
                days = HistoryTimeline.group(versions, now, ZoneOffset.UTC),
                loading = false,
            )
        }

    val share =
        ShareUiState(
            "Files you can read on the train: a reading list.",
            "example.org",
            listOf(RecentFile("u", "reading-notes.md", "", 0), RecentFile("v", "ideas.md", "", 0)),
            target = "u",
        )
}
