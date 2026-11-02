package com.scorchsoft.ssmarkdown.find

import androidx.compose.runtime.Immutable

/**
 * The find bar under the header. Its inputs survive rotation because the
 * state lives in the view model, not in the composable (robustness §5a, rule 5).
 *
 * @property current index into [matches] of the highlighted match, or -1.
 * @property replaceOpen the "⇄ Replace" toggle: row 2 shows only once it is opened.
 * @property wrapped set by the Next or Previous that wrapped round, so the
 *   bar can say so quietly; the next step, query or search clears it.
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
    val wrapped: FindWrap? = null,
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
                copy(matches = emptyList(), current = -1, invalidPattern = true, capped = false, wrapped = null)
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
                copy(
                    matches = result.matches,
                    capped = result.capped,
                    current = index,
                    invalidPattern = false,
                    wrapped = null,
                )
            }
        }

    /** The next match, wrapping round to the first after the last. */
    fun next(): FindUiState {
        if (matches.isEmpty()) return this
        val index = (current + 1).mod(matches.size)
        return copy(current = index, wrapped = if (index == 0 && current >= 0) FindWrap.ToFirst else null)
    }

    /** The previous match, wrapping round to the last before the first. */
    fun previous(): FindUiState {
        if (matches.isEmpty()) return this
        // With none current yet, Previous starts from the end rather than one before it.
        val index = if (current < 0) matches.size - 1 else (current - 1).mod(matches.size)
        return copy(current = index, wrapped = if (current == 0) FindWrap.ToLast else null)
    }
}

/** Which way the last Next or Previous wrapped round the document. */
enum class FindWrap {
    /** Next went past the last match, back to the first. */
    ToFirst,

    /** Previous went before the first match, round to the last. */
    ToLast,
}
