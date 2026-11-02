package com.scorchsoft.ssmarkdown.navigator

import androidx.compose.runtime.Immutable

/** The Navigator's two tabs. Outline is the default when the file has headings. */
enum class NavigatorTab { Outline, Files }

/**
 * One heading in the Outline tab.
 *
 * @property current true for the section the reader is in ("You're here").
 */
@Immutable
data class OutlineRow(
    val line: Int,
    val level: Int,
    val text: String,
    val current: Boolean = false,
)

/** Where a file row comes from: the three Files-tab groups. */
enum class FileRowKind { Draft, Recent, Folder }

/**
 * One file or draft in the Files tab or the quick switcher.
 *
 * @property detail the second line: a draft's first line, or a recent file's location.
 * @property ageMillis how long ago it was touched, for "· 1 h ago".
 * @property oldDraft drafts untouched for 30 days get a gentle "Old draft" label; never auto-deleted.
 * @property muted non-Markdown files (`.txt`) are muted and open in Code only.
 */
@Immutable
data class FileRow(
    val uri: String,
    val name: String,
    val detail: String,
    val kind: FileRowKind,
    val ageMillis: Long? = null,
    val dirty: Boolean = false,
    val oldDraft: Boolean = false,
    val muted: Boolean = false,
)

/** Quick-switcher results in the spec's order: headings, then recent files, then folder files. */
@Immutable
data class NavigatorResults(
    val headings: List<OutlineRow>,
    val recent: List<FileRow>,
    val folder: List<FileRow>,
) {
    /** True when nothing matched: "Nothing matches “xyz”". */
    val isEmpty: Boolean get() = headings.isEmpty() && recent.isEmpty() && folder.isEmpty()
}

/**
 * The Navigator sheet (ux-spec "Navigator").
 *
 * @property query the search field; empty shows the tabs.
 * @property folderName the open folder's name, for the "In product-docs" group.
 * @property hardwareKeyboard the "Ctrl K" hint shows only when one is attached.
 */
@Immutable
data class NavigatorUiState(
    val query: String = "",
    val tab: NavigatorTab = NavigatorTab.Outline,
    val outline: List<OutlineRow> = emptyList(),
    val drafts: List<FileRow> = emptyList(),
    val recent: List<FileRow> = emptyList(),
    val folder: List<FileRow> = emptyList(),
    val folderName: String? = null,
    val hardwareKeyboard: Boolean = false,
) {
    /** The quick switcher's groups for [query]. */
    val results: NavigatorResults
        get() =
            NavigatorResults(
                headings = outline.filter { WordStartMatcher.matches(it.text, query) },
                recent = (drafts + recent).filter { WordStartMatcher.matches(it.name, query) },
                folder = folder.filter { WordStartMatcher.matches(it.name, query) },
            )

    /** True while a query is typed: the groups replace the tabs. */
    val searching: Boolean get() = query.isNotBlank()
}
