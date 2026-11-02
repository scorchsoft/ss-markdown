package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.media.ExportFile
import com.scorchsoft.ssmarkdown.nav.AppScreen
import com.scorchsoft.ssmarkdown.preview.FormattedCopy

/**
 * One-shot effects only the Activity can perform: system pickers, the
 * clipboard, share sheets, haptics, navigation. The view model emits them
 * and never touches Android UI itself, so it runs on the host JVM.
 */
sealed interface EditorEvent {
    /** "Save as…": the system create-document picker, name prefilled from the first heading. */
    data class PickSaveLocation(
        val suggestedName: String,
    ) : EditorEvent

    /** ⋮ → Open file. */
    data object PickFileToOpen : EditorEvent

    /** ⋮ → Open folder. */
    data object PickFolder : EditorEvent

    /** Export section… : pick a location, then write [text]. */
    data class PickExportLocation(
        val suggestedName: String,
        val text: String,
    ) : EditorEvent

    /** Copy [text] as plain Markdown. */
    data class CopyText(
        val text: CharSequence,
    ) : EditorEvent

    /** Too big for the clipboard's Binder limit: share it as a file instead (robustness §5). */
    data class ShareAsFile(
        val name: String,
        val text: CharSequence,
    ) : EditorEvent

    /** Share [text]; [richText] offers it as HTML too (a `<pre>` of the Markdown). */
    data class ShareText(
        val text: CharSequence,
        val richText: Boolean,
    ) : EditorEvent

    /** Navigate to another screen. */
    data class Open(
        val screen: AppScreen,
    ) : EditorEvent

    /** Close the keyboard (Preview's Done key). */
    data object HideKeyboard : EditorEvent

    /**
     * The Link sheet closed (Done, Remove link or Cancel): its fields had the
     * keyboard, so give it back to the text, where the caret already is.
     */
    data object ReturnKeyboard : EditorEvent

    /** A light tick: mode switch, save success. */
    data object Tick : EditorEvent

    /** The error pattern (double tick), always beside a visible message. */
    data object ErrorTick : EditorEvent

    /** Leave the app. */
    data object Finish : EditorEvent

    /** "Copy formatted text": [copy]'s HTML with its plain-text twin, as one clip. */
    data class CopyFormatted(
        val copy: FormattedCopy,
    ) : EditorEvent

    /** Opens a web, mail or phone link in another app, after the user tapped Open on the chip. */
    data class OpenLink(
        val url: String,
    ) : EditorEvent

    /** Export images, separate files: `Downloads/SS Markdown/<folder>/`. */
    class SaveToDownloads(
        val folder: String,
        val files: List<ExportFile>,
    ) : EditorEvent

    /** Insert image: the system picker for one picture, starting in [initialFolder] (the document's folder). */
    data class PickImage(
        val initialFolder: String?,
    ) : EditorEvent

    /** "Allow access": the system folder picker, starting in [initialFolder] (the document's folder). */
    data class PickPictureFolder(
        val initialFolder: String?,
    ) : EditorEvent

    /** Export images as one ZIP, through the system picker, starting in [initialFolder] when remembered. */
    class SaveZip(
        val docName: String,
        val files: List<ExportFile>,
        val initialFolder: String?,
    ) : EditorEvent

    /** "Share instead", or Share from the viewer and the Mermaid sheet. */
    class ShareFiles(
        val files: List<ExportFile>,
    ) : EditorEvent

    /** The Mermaid sheet's Copy image. */
    class CopyImage(
        val file: ExportFile,
    ) : EditorEvent

    /** Display → Edit theme: the theme editor, on a copy of [themeId] when it is a built-in. */
    data class EditTheme(
        val themeId: String,
    ) : EditorEvent
}
