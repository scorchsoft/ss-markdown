package com.scorchsoft.ssmarkdown.find

/** The find bar's pure transitions; searching itself needs the text and runs in the view model. */
object FindReducer {
    /** [state] after [action]; null means the bar is closed. */
    fun reduce(
        state: FindUiState?,
        action: FindAction,
    ): FindUiState? =
        when (action) {
            FindAction.Open -> state ?: FindUiState()
            FindAction.Close -> null
            else -> state?.let { open(it, action) }
        }

    private fun open(
        state: FindUiState,
        action: FindAction,
    ): FindUiState =
        when (action) {
            is FindAction.Query -> state.copy(query = action.text, wrapped = null)
            is FindAction.Replacement -> state.copy(replacement = action.text)
            FindAction.ToggleMatchCase -> state.copy(options = state.options.copy(matchCase = !state.options.matchCase))
            FindAction.ToggleWholeWord -> state.copy(options = state.options.copy(wholeWord = !state.options.wholeWord))
            FindAction.ToggleRegex -> state.copy(options = state.options.copy(regex = !state.options.regex))
            FindAction.ToggleReplace -> state.copy(replaceOpen = !state.replaceOpen)
            FindAction.Next -> state.next()
            FindAction.Previous -> state.previous()
            else -> state
        }
}
