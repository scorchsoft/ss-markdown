package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.find.FindAction
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.media.ExportImagesAction
import com.scorchsoft.ssmarkdown.media.MediaMenuAction
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction
import com.scorchsoft.ssmarkdown.preview.bridge.TableOp
import com.scorchsoft.ssmarkdown.settings.Appearance
import com.scorchsoft.ssmarkdown.settings.PreviewWrap
import com.scorchsoft.ssmarkdown.ui.strip.CursorStep
import com.scorchsoft.ssmarkdown.ui.strip.StripKey

/** The panes that have their own zoom. */
enum class Pane { Code, Preview }

/** The ⋮ menu, in the spec's order and groups (docs/ux-spec.md, "Top bar"). */
enum class MenuItem {
    New,
    OpenFile,
    OpenFolder,
    SaveAs,

    /** Only in folder mode while a file is unsaved: the ≡ panel's Save all, two taps from ⋮ (rubric #9). */
    SaveAll,
    FindReplace,
    VersionHistory,
    ExportPdf,
    ExportImages,
    Share,
    CopyRichText,
    Display,
    Settings,

    /** Only above 1.5× font scale, when Copy moves into ⋮. */
    Copy,
}

/** Everything the user (or the platform) can do on the editor screen. */
sealed interface EditorAction {
    /**
     * The actions whose effects are the Preview's (`PreviewEffects`): a
     * sub-hierarchy, so both effect tables are exhaustive `when`s with no
     * `else`, and a new action that nobody handles fails to compile
     * (code review CC-16).
     */
    sealed interface PreviewAction : EditorAction

    data class SelectMode(
        val mode: ViewMode,
    ) : EditorAction

    data object CopyTapped : EditorAction

    data object CopyLongPressed : EditorAction

    data object SaveTapped : EditorAction

    data object SaveLongPressed : EditorAction

    data object ToggleAutosaveThisFile : EditorAction

    data object OpenMenu : EditorAction

    /** Undo or Redo from the top row of the ⋮ menu; the menu stays open for another tap. */
    data class MenuHistory(
        val redo: Boolean,
    ) : EditorAction

    data class ChooseMenuItem(
        val item: MenuItem,
    ) : EditorAction

    data object DismissOverlay : EditorAction

    data object TitleTapped : EditorAction

    data class ResetZoom(
        val pane: Pane,
    ) : EditorAction

    data class StepZoom(
        val pane: Pane,
        val steps: Int,
    ) : EditorAction

    data class ZoomChanged(
        val pane: Pane,
        val percent: Int,
        val pinching: Boolean,
    ) : EditorAction

    data class AnchorChanged(
        val line: Int,
    ) : EditorAction

    data class SetAppearance(
        val appearance: Appearance,
    ) : EditorAction

    data class SetCodeWrap(
        val wrap: Boolean,
    ) : EditorAction

    // Banners.
    data object RecoveryGotIt : EditorAction

    data object RecoveryCompare : EditorAction

    data object RecoveryDiscard : EditorAction

    data object UndoDiscard : EditorAction

    /** "Discard them" on the banner for edits an earlier session could not apply. */
    data object DiscardKeptEdits : EditorAction

    /** "Open as a copy" on that banner: the edits, rebuilt into a new draft (R2-DS-3). */
    data object OpenKeptAsCopy : EditorAction

    /** "Discard anyway" when History could not keep a copy (R2-DS-1). */
    data object ConfirmNotKept : EditorAction

    data object ReviewConflict : EditorAction

    data object SaveAsFromBanner : EditorAction

    data object ShowFailureDetails : EditorAction

    data object RecreateFile : EditorAction

    data object DismissBanner : EditorAction

    /** "Open a file…" on a failed open: the picker, for the same file again or another. */
    data object OpenAnotherFile : EditorAction

    /** "Try again" on a failed open: the same file once more. */
    data object RetryOpen : EditorAction

    // The conflict sheet.
    data object MergeMine : EditorAction

    data object CompareConflict : EditorAction

    data object KeepTheirs : EditorAction

    data object AskUseMine : EditorAction

    data object ConfirmUseMine : EditorAction

    // Leaving with unsaved edits.
    data object BackPressed : EditorAction

    data object LeaveSave : EditorAction

    data object LeaveDiscard : EditorAction

    data object KeepEditing : EditorAction

    // The formatting strip.
    data class StripKeyTapped(
        val key: StripKey,
    ) : EditorAction

    data class SymbolTapped(
        val text: String,
    ) : EditorAction

    data class CursorStepped(
        val step: CursorStep,
    ) : EditorAction

    data class ImeVisibilityChanged(
        val visible: Boolean,
    ) : EditorAction

    data object BigFileChipTapped : EditorAction

    data object MessageShown : EditorAction

    data object MessageUndo : EditorAction

    /** A find-bar action. */
    data class Find(
        val action: FindAction,
    ) : EditorAction

    /** A Navigator action. */
    data class Navigator(
        val action: NavigatorAction,
    ) : EditorAction

    /** A finger went down on [pane]: in Split it now leads the scrolling. */
    data class PaneTouched(
        val pane: Pane,
    ) : PreviewAction

    /** The link chip's Open. */
    data object OpenLink : PreviewAction

    /** The link chip's Edit link: Code, at the link's line. */
    data object EditLink : PreviewAction

    /** The link chip's Copy link: the address to the clipboard, nothing opened. */
    data object CopyLink : PreviewAction

    /** A row of the Mermaid sheet. */
    data class MediaMenu(
        val action: MediaMenuAction,
    ) : PreviewAction

    /** An Export images sheet action. */
    data class ExportImages(
        val action: ExportImagesAction,
    ) : PreviewAction

    /** A table ⋯ sheet row. */
    data class Table(
        val op: TableOp,
    ) : PreviewAction

    /** "Preview paused · Retry". */
    data object RetryPreview : PreviewAction

    /** Display → Preview wrapping: Fluid, the A4 page, or No wrap. */
    data class SetPreviewWrap(
        val wrap: PreviewWrap,
    ) : PreviewAction

    /** Display → Preview theme. */
    data class SetPreviewTheme(
        val id: String,
    ) : PreviewAction

    /** Display → Edit theme… for the theme with [id] (a built-in starts a custom copy). */
    data class EditTheme(
        val id: String,
    ) : PreviewAction

    /** A folder drawer action. */
    data class Folder(
        val action: FolderAction,
    ) : EditorAction
}
