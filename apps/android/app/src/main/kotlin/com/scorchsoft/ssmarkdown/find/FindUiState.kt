package com.scorchsoft.ssmarkdown.find

import androidx.compose.runtime.Immutable

/**
 * The find bar under the header. Its inputs survive rotation because the
 * state lives in the view model, not in the composable (robustness §5a, rule 5).
 *
 * @property current index into [matches] of the highlighted match, or -1.
 * @property replaceOpen the "⇄ Replace" toggle: row 2 shows only once it is opened.
 */
@Immutable
data class FindUiState(
    val query: String = "",
    val replacement: String = "",
    val options: FindOptions = FindOptions(),
    val replaceOpen: Boolean = false,
    val matches: List<FindMatch> = emptyList(),
    val capped: Boolean = false,
    val current: Int = -1,
    val invalidPattern: Boolean = false,
) {
    /** The highlighted match, if any. */
    val currentMatch: FindMatch? get() = matches.getOrNull(current)

    /** With [result] from a fresh search, keeping the current match nearest [caret]. */
    fun withResult(
        result: FindResult,
        caret: Int,
    ): FindUiState =
        when (result) {
            FindResult.InvalidPattern -> {
                copy(matches = emptyList(), current = -1, invalidPattern = true, capped = false)
            }

            is FindResult.Found -> {
                val next = result.matches.indexOfFirst { it.start >= caret }
                val index =
                    if (next >= 0) {
                        next
                    } else if (result.matches.isEmpty()) {
                        -1
                    } else {
                        0
                    }
                copy(matches = result.matches, capped = result.capped, current = index, invalidPattern = false)
            }
        }

    /** The next match, wrapping round. */
    fun next(): FindUiState = if (matches.isEmpty()) this else copy(current = (current + 1).mod(matches.size))

    /** The previous match, wrapping round. */
    fun previous(): FindUiState = if (matches.isEmpty()) this else copy(current = (current - 1).mod(matches.size))
}
