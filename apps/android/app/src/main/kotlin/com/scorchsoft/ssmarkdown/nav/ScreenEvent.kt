package com.scorchsoft.ssmarkdown.nav

import java.io.File

/** One-shot effects from the History and Storage screens that need the Activity. */
sealed interface ScreenEvent {
    /** Back to the previous screen. */
    data object Back : ScreenEvent

    /** An export finished: let the user choose where to keep [file] (named [suggestedName]). */
    data class SaveZip(
        val file: File,
        val suggestedName: String,
    ) : ScreenEvent

    /** "Import history (.zip)": the system picker. */
    data object PickImportZip : ScreenEvent

    /** Show another screen. */
    data class Open(
        val screen: AppScreen,
    ) : ScreenEvent
}
