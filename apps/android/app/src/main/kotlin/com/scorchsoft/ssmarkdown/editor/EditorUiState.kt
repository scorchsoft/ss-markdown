package com.scorchsoft.ssmarkdown.editor

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.find.FindUiState
import com.scorchsoft.ssmarkdown.folder.FolderUiState
import com.scorchsoft.ssmarkdown.media.ExportImagesState
import com.scorchsoft.ssmarkdown.navigator.NavigatorUiState
import com.scorchsoft.ssmarkdown.preview.RendererState
import com.scorchsoft.ssmarkdown.preview.bridge.LinkTarget
import com.scorchsoft.ssmarkdown.preview.bridge.MediaKind
import com.scorchsoft.ssmarkdown.ui.strip.FormattingStripState

/**
 * Everything the editor screen draws, as one immutable value
 * (docs/engineering.md, unidirectional data flow). The document's text is
 * not in here: it lives in the `DocumentStore` and reaches the panes
 * through the hosts, so a state copy never copies a 20 MB string.
 *
 * @property title the file name, or "Untitled" for a draft.
 * @property save the Save slot and the status line.
 * @property breadcrumb the current section's heading path once scrolled past the first heading.
 * @property readingFraction how far through the document the top of the pane is, 0–1.
 * @property pinchPercent the live zoom while a pinch is in progress, else null.
 * @property banner at most one non-modal banner under the header.
 * @property overlay at most one menu, sheet or dialog.
 * @property message a short confirmation ("Saved to Documents"), or null.
 * @property preview the Preview pane's own state: its renderer, and whether it is being edited.
 */
@Immutable
data class EditorUiState(
    val title: String = "",
    val save: SavePresentation =
        SavePresentation(SaveButton.SaveAsDraft, SaveStatusLine.Draft, SaveTap.SaveAs, showDirtyDot = false),
    val breadcrumb: List<String> = emptyList(),
    val readingFraction: Float = 0f,
    val mode: ViewMode = ViewMode.Code,
    val codeZoom: Int = 100,
    val previewZoom: Int = 100,
    val defaultCodeZoom: Int = 100,
    val defaultPreviewZoom: Int = 100,
    val pinchPercent: Int? = null,
    val codeWrap: Boolean = true,
    val strip: FormattingStripState = FormattingStripState(),
    val banner: EditorBanner? = null,
    val overlay: EditorOverlay? = null,
    val find: FindUiState? = null,
    val navigator: NavigatorUiState? = null,
    val folder: FolderUiState? = null,
    val message: UserMessage? = null,
    val bigFile: Boolean = false,
    val tooLarge: TooLargeInfo? = null,
    val loading: Boolean = false,
    val isMarkdown: Boolean = true,
    val canUndo: Boolean = false,
    val preview: PreviewPaneState = PreviewPaneState(),
) {
    /** The strip drives the Preview: in Preview, or in Split while the page has the caret. */
    val previewTyping: Boolean
        get() = mode == ViewMode.Preview || (mode == ViewMode.Split && preview.editing)

    /** True while the document has edits not yet written to its file. */
    val isDirty: Boolean get() = save.showDirtyDot

    /** The Code pane's zoom chip shows only when it differs from the default (ux-spec "Zoom"). */
    val showCodeZoomChip: Boolean get() = codeZoom != defaultCodeZoom

    /** The Preview pane's zoom chip, by the same rule. */
    val showPreviewZoomChip: Boolean get() = previewZoom != defaultPreviewZoom
}

/**
 * The Preview pane's state.
 *
 * @property renderer running, restarted after a crash, or paused after repeated ones.
 * @property editing the page has a caret and the keyboard (tap to edit).
 * @property inHeading the page's caret is in a heading: the strip's H key turns it back into a paragraph.
 */
@Immutable
data class PreviewPaneState(
    val renderer: RendererState = RendererState.RUNNING,
    val editing: Boolean = false,
    val inHeading: Boolean = false,
)

/** A non-modal banner under the header. One at a time; the newest wins. */
sealed interface EditorBanner {
    /**
     * "Your unsaved edits are back" (ux-spec "Recovery").
     *
     * @property closedBySwipe true when the app was swiped from Recents: "SS Markdown was closed before you saved".
     */
    data class Recovered(
        val atMillis: Long,
        val closedBySwipe: Boolean,
    ) : EditorBanner

    /** "<name> changed in another app. Nothing is overwritten. Review". */
    data object ChangedOutside : EditorBanner

    /** "Your text is safe on this phone. Save as… · Details". */
    data class SaveFailed(
        val problem: SaveProblem,
    ) : EditorBanner

    /** "<name> was moved or deleted. Save as… · Recreate". */
    data object FileMissing : EditorBanner

    /** "Saved 3 files · Skipped roadmap.md: changed in another app · Review". */
    data class SaveAllResult(
        val saved: Int,
        val skipped: List<String>,
    ) : EditorBanner
}

/** Why a dirty buffer is about to leave memory: the Save / Don't save / Keep editing prompt. */
sealed interface PendingLeave {
    /** Back from the editor: the app closes. */
    data object Exit : PendingLeave

    /** Opening a file outside the open folder. */
    data class OpenOther(
        val uri: String,
    ) : PendingLeave

    /** ⋮ → New. */
    data object NewDocument : PendingLeave

    /** Closing the open folder with dirty files. */
    data object CloseFolder : PendingLeave
}

/** At most one menu, sheet or dialog over the editor. */
sealed interface EditorOverlay {
    /** The ⋮ menu. */
    data object Menu : EditorOverlay

    /** Long-press Save: Save as… · Autosave this file. */
    data class SaveMenu(
        val autosaveThisFile: Boolean,
    ) : EditorOverlay

    /** Long-press Copy: Copy as rich text. */
    data object CopyMenu : EditorOverlay

    /** The conflict sheet (ux-spec "File changed outside the app"). */
    data class Conflict(
        val summary: ConflictSummary,
    ) : EditorOverlay

    /** "Use my version…" asks first: it overwrites the other app's change. */
    data object ConfirmUseMine : EditorOverlay

    /** Save / Don't save / Keep editing. */
    data class DirtyPrompt(
        val leave: PendingLeave,
    ) : EditorOverlay

    /** ⋮ → Display. */
    data object Display : EditorOverlay

    /** The section menu for a heading: Copy section · rich text · Select · Export. */
    data class SectionMenu(
        val headingLine: Int,
        val heading: String,
        val lineCount: Int,
        val nextHeading: String?,
    ) : EditorOverlay

    /** Gestures rule 3: Open · Edit link, for a link tapped in Preview. */
    data class LinkChip(
        val target: LinkTarget,
        val line: Int,
    ) : EditorOverlay

    /** Long-press on a diagram or picture (ux-spec "Mermaid"). */
    data class MediaMenu(
        val kind: MediaKind,
        val line: Int,
        val title: String,
        val pngScale: Int = 2,
    ) : EditorOverlay

    /** The table toolbar's ⋯: insert, move, align and delete. */
    data class TableMenu(
        val line: Int,
    ) : EditorOverlay

    /** ⋮ → Export images… */
    data class ExportImages(
        val state: ExportImagesState,
    ) : EditorOverlay

    /** What Big-file mode pauses and why. */
    data object BigFileInfo : EditorOverlay

    /** Details of a failed save. */
    data class SaveFailureDetails(
        val problem: SaveProblem,
    ) : EditorOverlay
}

/**
 * The numbers the conflict sheet shows.
 *
 * @property theirsAdded lines the other app added, relative to what the edits started from.
 * @property mineChanges changes in the unsaved buffer.
 * @property cleanMerges changes that merge by themselves.
 * @property overlaps changes both sides made to the same lines.
 * @property canMerge false when the base copy was lost: Merge is not offered.
 */
@Immutable
data class ConflictSummary(
    val theirsAdded: Int,
    val theirsRemoved: Int,
    val mineChanges: Int,
    val cleanMerges: Int,
    val overlaps: Int,
    val canMerge: Boolean,
)

/** "This 180 MB file needs about 400 MB, and this phone allows 256 MB". */
@Immutable
data class TooLargeInfo(
    val fileBytes: Long,
    val neededBytes: Long,
    val allowedBytes: Long,
)

/** A short message at the foot of the screen; [undo] offers an Undo action. */
@Immutable
data class UserMessage(
    val kind: MessageKind,
    val argument: String = "",
    val undo: Boolean = false,
    val id: Long = 0,
)

/** The messages the editor can show; each maps to one string resource. */
enum class MessageKind {
    SavedTo,
    AllSaved,
    Copied,
    CopiedRichText,
    CopyTooLarge,
    EditsDiscarded,
    SectionCopied,
    ReplacedAll,
    Restored,
    Renamed,
    Deleted,
    Duplicated,
    ActionFailed,
    PreviewPending,
    AutosaveThisFileOn,
    AutosaveThisFileOff,
    PreviewRestarted,
    EditRefused,
    LinkBlocked,
    ImagesExported,
    ImagesExportFailed,
    ImageSaved,
    ImageCopied,
    NothingToExport,
}
