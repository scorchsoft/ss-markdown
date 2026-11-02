package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.editor.SaveProblem
import com.scorchsoft.ssmarkdown.editor.SaveStatusLine
import com.scorchsoft.ssmarkdown.ui.LocalNow
import com.scorchsoft.ssmarkdown.ui.RelativeTime
import com.scorchsoft.ssmarkdown.ui.relativeTimeText

/**
 * True for the states drawn in the text-safe red: a failed save and paused
 * autosave. One rule for the header, the compact header and the landscape
 * strip, so the same state is never red in one and grey in another (UX2-7).
 */
fun SaveStatusLine.isError(): Boolean = this is SaveStatusLine.Failed || this is SaveStatusLine.AutosavePaused

/**
 * The words for a [SaveStatusLine]; times follow [LocalNow], which the Activity ticks.
 *
 * @param besideBadge the READ-ONLY badge is drawn next to the line, so the
 *   line leaves "read-only" out and keeps room for the source (UX2-14).
 */
@Suppress("CyclomaticComplexMethod") // One branch per row of the Save-state table.
@Composable
fun statusText(
    status: SaveStatusLine,
    besideBadge: Boolean = false,
): String =
    when (status) {
        is SaveStatusLine.Saved -> {
            val ago = relativeTimeText(ticking(status.atMillis))
            stringResource(if (status.autosaved) R.string.status_autosaved else R.string.status_saved, ago)
        }

        is SaveStatusLine.Opened -> {
            stringResource(R.string.status_opened, relativeTimeText(ticking(status.atMillis)))
        }

        SaveStatusLine.Unsaved -> {
            stringResource(R.string.status_unsaved)
        }

        SaveStatusLine.Saving -> {
            stringResource(R.string.status_saving)
        }

        SaveStatusLine.Draft -> {
            stringResource(R.string.status_draft)
        }

        is SaveStatusLine.UnsavedReadOnly -> {
            when {
                besideBadge && status.source != null -> stringResource(R.string.status_unsaved_from, status.source)
                besideBadge -> stringResource(R.string.status_unsaved)
                status.source != null -> stringResource(R.string.status_unsaved_read_only_from, status.source)
                else -> stringResource(R.string.status_unsaved_read_only)
            }
        }

        is SaveStatusLine.ReadOnly -> {
            status.source?.let { stringResource(R.string.status_read_only_from, it) }
                ?: stringResource(R.string.status_read_only)
        }

        is SaveStatusLine.Failed -> {
            stringResource(R.string.status_failed, problemText(status.problem))
        }

        is SaveStatusLine.AutosavePaused -> {
            stringResource(R.string.status_autosave_paused, problemText(status.problem))
        }
    }

/** The short "<reason>" for a save problem. */
@Composable
fun problemText(problem: SaveProblem): String =
    stringResource(
        when (problem) {
            SaveProblem.StorageFull -> R.string.problem_storage_full
            SaveProblem.PermissionLost -> R.string.problem_permission
            SaveProblem.FileMissing -> R.string.problem_missing
            SaveProblem.VerifyFailed -> R.string.problem_verify
            SaveProblem.BackupFailed -> R.string.problem_backup
            SaveProblem.ProviderError -> R.string.problem_provider
            SaveProblem.TimedOut -> R.string.problem_timed_out
            SaveProblem.ChangedOutside -> R.string.problem_changed
            SaveProblem.ReadOnly -> R.string.problem_read_only
            SaveProblem.TargetOpen -> R.string.problem_target_open
        },
    )

/** The suffix appended to a breadcrumb when there is something to say ("· Unsaved"); null when clean. */
@Composable
fun breadcrumbSuffix(status: SaveStatusLine): String? =
    when (status) {
        SaveStatusLine.Unsaved, is SaveStatusLine.UnsavedReadOnly -> stringResource(R.string.status_unsaved)
        SaveStatusLine.Saving -> stringResource(R.string.status_saving)
        is SaveStatusLine.ReadOnly -> stringResource(R.string.status_read_only)
        is SaveStatusLine.Failed, is SaveStatusLine.AutosavePaused -> statusText(status)
        else -> null
    }

@Composable
private fun ticking(then: Long): RelativeTime = RelativeTime.between(then, LocalNow.current)
