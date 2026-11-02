package com.scorchsoft.ssmarkdown.find

/** What the find bar can do. */
sealed interface FindAction {
    data object Open : FindAction

    data object Close : FindAction

    data class Query(
        val text: String,
    ) : FindAction

    data class Replacement(
        val text: String,
    ) : FindAction

    data object ToggleMatchCase : FindAction

    data object ToggleWholeWord : FindAction

    data object ToggleRegex : FindAction

    data object ToggleReplace : FindAction

    data object Next : FindAction

    data object Previous : FindAction

    data object ReplaceOne : FindAction

    data object ReplaceAll : FindAction
}
