package com.scorchsoft.ssmarkdown.editor

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.files.document.CheckpointResult
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.find.FindUiState
import com.scorchsoft.ssmarkdown.folder.FolderUiState
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.media.ExportImagesState
import com.scorchsoft.ssmarkdown.navigator.NavigatorUiState
import com.scorchsoft.ssmarkdown.preview.RendererState
import com.scorchsoft.ssmarkdown.preview.bridge.LinkTarget
import com.scorchsoft.ssmarkdown.preview.bridge.MediaKind
import com.scorchsoft.ssmarkdown.settings.CopyFormat
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
 * @property readingFraction the leading pane's scroll position over its scrollable range, 0–1 (1 at the very bottom).
 * @property pinchPercent the live zoom while a pinch is in progress, else null.
 * @property banner at most one non-modal banner under the header.
 * @property overlay at most one menu, sheet or dialog.
 * @property message a short confirmation ("Saved to Documents"), or null.
 * @property preview the Preview pane's own state: its renderer, and whether it is being edited.
 * @property keyboardUp the soft keyboard is on screen.
 * @property typingPane in Split, the pane the keyboard was opened for, until the keyboard closes, the
 *   mode changes or another document opens (ux-spec "Split while typing"); null otherwise. Whether the
 *   other pane hides is the layout's call: the setting, and never side by side.
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
    val canRedo: Boolean = false,
    val preview: PreviewPaneState = PreviewPaneState(),
    val historyOn: Boolean = true,
    /** What Copy put on the clipboard last: long-press Copy repeats it, the chooser lists it first. */
    val copyFormat: CopyFormat = CopyFormat.Markdown,
    val keyboardUp: Boolean = false,
    val typingPane: Pane? = null,
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
 * @property picturesNeedFolder the page has pictures it cannot show without the document's folder:
 *   ⋮ offers "Show pictures beside this file…".
 */
@Immutable
data class PreviewPaneState(
    val renderer: RendererState = RendererState.RUNNING,
    val editing: Boolean = false,
    val inHeading: Boolean = false,
    val picturesNeedFolder: Boolean = false,
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

    /**
     * Edits from an earlier session that could not be applied to this
     * file (`Recovery.Kept`): kept untouched until the user edits the file
     * or discards them (DS-3). "Discard them · Got it".
     */
    data object EditsKeptAside : EditorBanner

    /** The recovery journal stopped working: "Recovery copies have stopped · Save as…". */
    data object RecoveryCopiesStopped : EditorBanner

    /**
     * Edits that could not go back to their file, shown in a new draft
     * (R2-DS-2, R2-DS-3): "Recovered from <name> · Save as… · Got it".
     *
     * @property source the file's name, or null if it is not known.
     * @property complete false when only the typed text could be read.
     */
    data class RecoveredCopy(
        val source: String?,
        val reason: CopyReason,
        val complete: Boolean,
    ) : EditorBanner

    /** "Saved 3 files · Skipped roadmap.md: changed in another app · Review". */
    data class SaveAllResult(
        val saved: Int,
        val skipped: List<SkippedSave>,
    ) : EditorBanner

    /**
     * A file could not be opened (M2): what happened, why, and the way
     * forward ("Open a file…" or "Try again"), never a bare "That didn't work".
     *
     * @property uri the file that failed, for Try again.
     */
    data class OpenFailed(
        val uri: String,
        val problem: OpenProblem,
    ) : EditorBanner

    /**
     * The file opened read-only because of its bytes: UTF-32, which the
     * editor will not write ([invalid] false), or bytes that are not valid
     * [encoding] ([invalid] true). "Save as… · Got it".
     */
    data class EncodingReadOnly(
        val encoding: String,
        val invalid: Boolean,
    ) : EditorBanner
}

/**
 * A file Save all left unsaved (J7): which, and why, so the banner can say
 * "Skipped roadmap.md: changed in another app" and Review can open it.
 */
data class SkippedSave(
    val uri: String,
    val name: String,
    val problem: SaveProblem,
)

/** Why a file could not be opened, and so what the banner offers. */
enum class OpenProblem(
    /** True when the way forward is to pick the file again; false when it is to try again. */
    val picksAnother: Boolean,
) {
    /** Moved, deleted, or its provider is gone. */
    Missing(picksAnother = true),

    /** The grant was revoked or never covered it; picking it again grants access. */
    NoPermission(picksAnother = true),

    /** A slow cloud provider did not answer in time. */
    TimedOut(picksAnother = false),

    /** Anything else the provider failed with. */
    Unreadable(picksAnother = false),
    ;

    /** Maps provider failures. */
    companion object {
        /** The problem a provider [failure] means for the user. */
        fun of(failure: ProviderResult.Failure): OpenProblem =
            when (failure) {
                ProviderResult.NotFound -> Missing
                ProviderResult.PermissionDenied -> NoPermission
                ProviderResult.TimedOut -> TimedOut
                else -> Unreadable
            }
    }
}

/** Why edits are shown as a copy rather than in their own file. */
enum class CopyReason {
    /** The file cannot be reached after a restart: grant lost, moved, deleted, provider gone. */
    Unreachable,

    /** Recovery could not apply them to the file (`Recovery.Kept`). */
    NotApplied,

    /** Opened from Storage's set-aside edits. */
    SetAside,
}

/** A step that would drop unsaved edits, waiting on "History couldn't keep a copy · go on?" (R2-DS-1). */
sealed interface NotKeptStep {
    /** "Keep the file's version". */
    data object KeepTheirs : NotKeptStep

    /** "Discard edits" on the recovery banner. */
    data object DiscardRecovered : NotKeptStep

    /** MergeResolve's Done, with the merge it would apply. */
    data class FinishMerge(
        val text: String,
        val madeFromVersion: Long,
    ) : NotKeptStep

    /** "Don't save", then [leave]. */
    data class DontSave(
        val leave: PendingLeave,
    ) : NotKeptStep
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

    /** The Copy chooser: Copy Markdown · Copy formatted text, [first] (the last used) on top. */
    data class CopyChooser(
        val first: CopyFormat,
    ) : EditorOverlay

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

    /**
     * The View sheet (⋮ → View, the ⋮ "Aa" icon, or long-press a mode). Its
     * layout is fixed; [highlight] only briefly tints that pane's Lines row
     * (long-press Code or Preview), and null tints nothing.
     */
    data class Display(
        val highlight: Pane? = null,
    ) : EditorOverlay

    /**
     * The section menu for a heading: Copy section as Markdown · as formatted
     * text ([copyFirst], the last used, on top) · Select · Export.
     */
    data class SectionMenu(
        val headingLine: Int,
        val heading: String,
        val lineCount: Int,
        val nextHeading: String?,
        val copyFirst: CopyFormat = CopyFormat.Markdown,
    ) : EditorOverlay

    /**
     * Gestures rule 3: Open · Copy link · Edit link, for a link tapped in
     * Preview. An external link always asks first, even with "Tap to edit"
     * off (SEC-7); [canEdit] is false then, and Edit link is not offered.
     */
    data class LinkChip(
        val target: LinkTarget,
        val line: Int,
        val canEdit: Boolean = true,
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

    /**
     * The strip's Link prompt, for [selection] (host offsets, taken when
     * Link was tapped, in Code or from the page's selection in Preview).
     */
    data class LinkPrompt(
        val fields: LinkFields,
        val selection: TextSelection,
    ) : EditorOverlay

    /**
     * Insert image picked [picked], which is outside the document's folder:
     * "Copy into this file's folder" or Cancel (docs/ux-spec.md, "Pictures").
     */
    data class CopyPicture(
        val picked: String,
    ) : EditorOverlay

    /** Details of a failed save. */
    data class SaveFailureDetails(
        val problem: SaveProblem,
    ) : EditorOverlay

    /**
     * History could not keep a copy of edits [step] would drop, so nothing
     * has happened yet: go on, or keep the edits (R2-DS-1).
     *
     * @property checkpoint what History answered, which words the question.
     */
    data class NotKept(
        val step: NotKeptStep,
        val checkpoint: CheckpointResult,
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

/**
 * "This 180 MB file needs about 400 MB, and this phone allows 256 MB". When
 * the file fits the ceiling but not what is free now ([usableNowBytes]),
 * the message says that instead, so it never reads as a contradiction.
 */
@Immutable
data class TooLargeInfo(
    val fileBytes: Long,
    val neededBytes: Long,
    val allowedBytes: Long,
    val usableNowBytes: Long = allowedBytes,
) {
    /** True when the file is within the ceiling and only what is open now stands in the way. */
    val fitsWhenFreed: Boolean get() = neededBytes <= allowedBytes
}

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
    CopiedFormatted,
    CopiedFormattedShortened,
    FormattedFailed,
    CopyTooLarge,
    PasteTooLarge,
    EditsDiscarded,
    EditsDiscardedNoHistory,
    EditsDiscardedNotKept,
    DraftDeleted,
    SectionCopied,
    SectionCopiedFormatted,
    ReplacedAll,
    Restored,
    Renamed,
    SaveBeforeRename,
    Deleted,
    Duplicated,
    ActionFailed,
    PreviewPending,
    AutosaveThisFileOn,
    AutosaveThisFileOff,
    PreviewRestarted,
    EditRefused,
    LinkBlocked,
    LinkCopied,
    ImagesExported,
    ImagesExportFailed,
    ImageSaved,
    ImageCopied,
    NothingToExport,
    PdfFailed,
    ExportStopped,
    PictureFolderGranted,
    PictureFolderUnrelated,
    PictureTooLarge,
    PictureNotImage,
    PictureFailed,
    PicturesStayedWithDraft,
    PictureInsertStopped,
    PictureCopiedNotLinked,
}
