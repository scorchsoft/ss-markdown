package com.scorchsoft.ssmarkdown.share

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.settings.AppendPosition
import com.scorchsoft.ssmarkdown.settings.AppendPrefix
import com.scorchsoft.ssmarkdown.settings.RecentFile

/** Where the share sheet is in its short life. */
sealed interface SharePhase {
    /** Choosing a target and options. */
    data object Choosing : SharePhase

    /** Writing through the save pipeline. */
    data object Working : SharePhase

    /** "Added to <file> · Open", shown for about 1.5 s before the sheet closes. */
    data class Done(
        val name: String,
        val uri: String,
    ) : SharePhase

    /** The target can't take it (read-only or changed elsewhere): offer New note instead. */
    data class Blocked(
        val name: String,
        val conflict: Boolean,
    ) : SharePhase

    /** The write failed; nothing was changed. */
    data class Failed(
        val name: String,
    ) : SharePhase

    /** What was shared can't be used: too large, or not text. */
    data class Unusable(
        val tooLargeBytes: Long?,
    ) : SharePhase
}

/**
 * The share-in sheet (the ShareIn board).
 *
 * @property target the chosen file's URI, or null for New note.
 * @property source where the text came from ("example.org"), when known.
 */
@Immutable
data class ShareUiState(
    val text: String = "",
    val source: String? = null,
    val targets: List<RecentFile> = emptyList(),
    val target: String? = null,
    val position: AppendPosition = AppendPosition.Bottom,
    val prefix: AppendPrefix = AppendPrefix.None,
    val phase: SharePhase = SharePhase.Choosing,
)

/** What the share sheet can do. */
sealed interface ShareAction {
    data class ChooseTarget(
        val uri: String?,
    ) : ShareAction

    data object AnotherFile : ShareAction

    data class SetPosition(
        val position: AppendPosition,
    ) : ShareAction

    data object TogglePrefix : ShareAction

    data object Confirm : ShareAction

    data object Cancel : ShareAction

    data object OpenResult : ShareAction
}

/** The share sheet's pure transitions. */
object ShareReducer {
    /** [state] after [action]. */
    fun reduce(
        state: ShareUiState,
        action: ShareAction,
    ): ShareUiState =
        when (action) {
            is ShareAction.ChooseTarget -> {
                state.copy(target = action.uri, phase = SharePhase.Choosing)
            }

            is ShareAction.SetPosition -> {
                state.copy(position = action.position)
            }

            ShareAction.TogglePrefix -> {
                state.copy(
                    prefix =
                        if (state.prefix ==
                            AppendPrefix.None
                        ) {
                            AppendPrefix.DateHeading
                        } else {
                            AppendPrefix.None
                        },
                )
            }

            else -> {
                state
            }
        }

    /** The source shown under the quote: a URL's host if the text holds one, else the subject. */
    fun sourceOf(
        text: String,
        subject: String?,
    ): String? {
        val url =
            Regex("https?://([^/\\s]+)")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.removePrefix("www.")
        return url ?: subject
    }
}
