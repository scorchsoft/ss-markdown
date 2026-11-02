package com.scorchsoft.ssmarkdown.navigator

/** The Navigator's pure transitions; opening it and jumping need the document and run in the view model. */
object NavigatorReducer {
    /** [state] after [action]; null means the sheet is closed. */
    fun reduce(
        state: NavigatorUiState?,
        action: NavigatorAction,
    ): NavigatorUiState? =
        when (action) {
            NavigatorAction.Close,
            NavigatorAction.TopOfDocument,
            is NavigatorAction.JumpToHeading,
            is NavigatorAction.OpenFile,
            NavigatorAction.OpenPicker,
            -> null

            is NavigatorAction.Query -> state?.copy(query = action.text)

            is NavigatorAction.SelectTab -> state?.copy(tab = action.tab)

            else -> state
        }
}
