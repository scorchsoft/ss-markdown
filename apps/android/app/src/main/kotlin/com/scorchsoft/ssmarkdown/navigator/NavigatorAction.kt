package com.scorchsoft.ssmarkdown.navigator

/** What the Navigator can do. */
sealed interface NavigatorAction {
    data object Close : NavigatorAction

    data class Query(
        val text: String,
    ) : NavigatorAction

    data class SelectTab(
        val tab: NavigatorTab,
    ) : NavigatorAction

    data object TopOfDocument : NavigatorAction

    data class JumpToHeading(
        val line: Int,
    ) : NavigatorAction

    /** The ⋯ on a heading row, or a long-press: the section menu. */
    data class HeadingMenu(
        val line: Int,
    ) : NavigatorAction

    data class OpenFile(
        val uri: String,
    ) : NavigatorAction

    /** Enter in the search field: the first result. */
    data object OpenFirstResult : NavigatorAction

    /** "Open a file…": the system picker. */
    data object OpenPicker : NavigatorAction

    data class DeleteDraft(
        val uri: String,
    ) : NavigatorAction

    data class CopySection(
        val line: Int,
        val richText: Boolean,
    ) : NavigatorAction

    data class SelectSection(
        val line: Int,
    ) : NavigatorAction

    data class ExportSection(
        val line: Int,
    ) : NavigatorAction
}
