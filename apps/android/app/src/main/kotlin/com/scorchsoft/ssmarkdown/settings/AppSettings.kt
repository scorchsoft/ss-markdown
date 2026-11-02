package com.scorchsoft.ssmarkdown.settings

import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.history.versions.HistoryPolicy
import com.scorchsoft.ssmarkdown.history.versions.StorageLimit

/** "When a file opens, show": Code, Preview or the view each file last used. */
enum class OpenWith { Code, Preview, LastUsed }

/** "On launch": reopen the last file, or start a new scratch draft. */
enum class OnLaunch { ReopenLast, NewDraft }

/** "Write to the file" (robustness §3a): only on Save, or after a pause in typing. */
enum class SaveMode { WhenITapSave, Autosave }

/** Display → Appearance. System follows the phone's dark-mode setting. */
enum class Appearance { System, Light, Dark }

/**
 * Every user setting, as one immutable value (docs/ux-spec.md, "Settings").
 * The defaults here are the product's defaults; a fresh install reads
 * exactly these.
 *
 * @property history the Storage and history settings that version history obeys.
 * @property autosaveOverrides "Autosave this file" choices, by document URI; absent means "use the default".
 * @property recentFiles most recent first; Navigator → Files and Share → Append to.
 * @property lastFile the document reopened on launch, if any.
 * @property previewThemeId the preview theme (Display → Preview theme): a built-in or custom theme's id.
 * @property previewWrap Display → Preview wrapping: Fluid, the A4 page, or No wrap.
 * @property export Export images' remembered choices.
 * @property copyFormat what Copy put on the clipboard last; the Copy chooser lists it first.
 */
data class AppSettings(
    val openWith: OpenWith = OpenWith.LastUsed,
    val onLaunch: OnLaunch = OnLaunch.ReopenLast,
    val defaultZoomCode: Int = 100,
    val defaultZoomPreview: Int = 100,
    val lineNumbers: Boolean = true,
    val codeWrap: Boolean = true,
    val saveMode: SaveMode = SaveMode.WhenITapSave,
    val tapToEditPreview: Boolean = true,
    val scrollPanesTogether: Boolean = true,
    val appearance: Appearance = Appearance.System,
    val history: HistoryPolicy = HistoryPolicy(),
    val autosaveOverrides: Map<String, Boolean> = emptyMap(),
    val recentFiles: List<RecentFile> = emptyList(),
    val lastFile: String? = null,
    val share: ShareDefaults = ShareDefaults(),
    val previewThemeId: String = "clean",
    val previewWrap: PreviewWrap = PreviewWrap.Fluid,
    val export: ExportDefaults = ExportDefaults(),
    val copyFormat: CopyFormat = CopyFormat.Markdown,
) {
    /** Whether [uri] autosaves: drafts always do; otherwise its override, else the global mode. */
    fun autosaves(
        uri: String,
        isDraft: Boolean,
    ): Boolean = isDraft || autosaveOverrides[uri] ?: (saveMode == SaveMode.Autosave)

    /** The view a file opens in when it has no remembered one (ux-spec "Saving and safety"). */
    fun firstViewFor(fromAnotherApp: Boolean): ViewMode =
        when (openWith) {
            OpenWith.Code -> ViewMode.Code
            OpenWith.Preview -> ViewMode.Preview
            OpenWith.LastUsed -> if (fromAnotherApp) ViewMode.Preview else ViewMode.Code
        }

    /** The history limit as a short label key; see [HistoryLimits]. */
    val totalLimitBytes: Long? get() = (history.totalLimit as? StorageLimit.Bytes)?.bytes

    companion object {
        /** How many recent files are remembered. */
        const val MAX_RECENT_FILES: Int = 20
    }
}

/**
 * A file the user worked on recently.
 *
 * @property uri its document URI.
 * @property name its display name.
 * @property location a short "where" for the row ("Documents", "product-docs/specs").
 * @property openedAtMillis when it was last opened.
 * @property pinned pinned files lead the list.
 */
data class RecentFile(
    val uri: String,
    val name: String,
    val location: String,
    val openedAtMillis: Long,
    val pinned: Boolean = false,
)

/** Where shared text goes; remembered after each use (ux-spec "Share into SS Markdown"). */
enum class AppendPosition { Bottom, Top }

/** What precedes appended text. */
enum class AppendPrefix { None, DateHeading }

/**
 * The share sheet's remembered choices.
 *
 * @property lastTarget the last file appended to, if any.
 * @property directShare Settings → Sharing: recent files appear in Android's share sheet and the
 *   launcher's long-press menu (ADR 0017). On by default.
 */
data class ShareDefaults(
    val lastTarget: String? = null,
    val position: AppendPosition = AppendPosition.Bottom,
    val prefix: AppendPrefix = AppendPrefix.None,
    val directShare: Boolean = true,
)

/**
 * What Copy puts on the clipboard (ux-spec "Top bar"): the Markdown source
 * exactly, or the rendered document as formatted text (HTML with a plain-text
 * twin) for mail and document editors.
 */
enum class CopyFormat { Markdown, Formatted }

/** Export images → Diagrams as. */
enum class DiagramFormat { Png, Svg, Both }

/** Export images → Save as. */
enum class ExportDestination { SeparateFiles, Zip }

/**
 * Export images' remembered choices (ux-spec "Export images"). The PNG
 * scale is also what the Mermaid sheet's Save as PNG uses.
 *
 * @property zipFolder the folder the last ZIP went to, offered first next time.
 */
data class ExportDefaults(
    val format: DiagramFormat = DiagramFormat.Png,
    val scale: Int = 2,
    val destination: ExportDestination = ExportDestination.SeparateFiles,
    val zipFolder: String? = null,
)

/** The choices Storage offers for "History limit" and the per-file cap, in order. */
object HistoryLimits {
    private const val MB = 1_000_000L

    /** 250 MB, 500 MB, 1 GB, 2 GB, 5 GB and No limit; 1 GB is the default. */
    val total: List<StorageLimit> =
        listOf(
            StorageLimit.Bytes(250 * MB),
            StorageLimit.Bytes(500 * MB),
            StorageLimit.Bytes(1_000 * MB),
            StorageLimit.Bytes(2_000 * MB),
            StorageLimit.Bytes(5_000 * MB),
            StorageLimit.None,
        )

    /** "Forget history of files unused for": Never (null), then days. */
    val forgetAfterDays: List<Int?> = listOf(null, 30, 90, 180, 365)
}

/** Records [uri] as the most recently opened file, keeping pins and the cap. */
fun AppSettings.withRecent(file: RecentFile): AppSettings {
    val pinned = recentFiles.firstOrNull { it.uri == file.uri }?.pinned ?: file.pinned
    val rest = recentFiles.filterNot { it.uri == file.uri }
    val list = (listOf(file.copy(pinned = pinned)) + rest).take(AppSettings.MAX_RECENT_FILES)
    return copy(recentFiles = list, lastFile = file.uri)
}

/**
 * How Preview lays out its lines (ux-spec "Display sheet"): [Fluid] wraps
 * to the pane, [Page] shows the A4 page as it will print, and [NoWrap]
 * keeps code lines and tables at full width with a horizontal scroll.
 */
enum class PreviewWrap {
    Fluid,
    Page,
    NoWrap,
}
