package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.nav.AppScreen

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

    /** Share [text]; [richText] offers it as HTML too ("Copy as rich text" until Preview renders HTML). */
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

    /** A light tick: mode switch, save success. */
    data object Tick : EditorEvent

    /** The error pattern (double tick), always beside a visible message. */
    data object ErrorTick : EditorEvent

    /** Leave the app. */
    data object Finish : EditorEvent
}
