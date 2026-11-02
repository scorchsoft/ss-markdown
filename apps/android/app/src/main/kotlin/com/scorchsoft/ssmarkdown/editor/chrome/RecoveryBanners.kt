package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.editor.CopyReason
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorBanner

/**
 * Edits an earlier session could not apply to this file (DS-3): kept
 * untouched, and now readable too, by opening them as a copy (R2-DS-3).
 */
@Composable
internal fun KeptAsideBanner(
    onAction: (EditorAction) -> Unit,
    modifier: Modifier,
) {
    Callout(stringResource(R.string.kept_title), stringResource(R.string.kept_body_copy), Tone.Caution, modifier) {
        BannerButton(R.string.kept_open_copy) { onAction(EditorAction.OpenKeptAsCopy) }
        BannerButton(R.string.kept_discard) { onAction(EditorAction.DiscardKeptEdits) }
        BannerButton(R.string.recovered_got_it) { onAction(EditorAction.DismissBanner) }
    }
}

/**
 * The draft on screen holds edits that could not go back to their file
 * (R2-DS-2, R2-DS-3). Says where they came from, and whether only the
 * typed text could be read; Save as… puts them somewhere for good.
 */
@Composable
internal fun RecoveredCopyBanner(
    banner: EditorBanner.RecoveredCopy,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier,
) {
    val source = banner.source ?: stringResource(R.string.copy_unknown_file)
    val (title, body) =
        when (banner.reason) {
            CopyReason.Unreachable -> {
                stringResource(R.string.copy_unreachable_title, source) to
                    stringResource(R.string.copy_unreachable_body)
            }

            CopyReason.NotApplied -> {
                stringResource(R.string.copy_not_applied_title) to
                    stringResource(R.string.copy_not_applied_body, source)
            }

            CopyReason.SetAside -> {
                stringResource(R.string.copy_set_aside_title) to
                    stringResource(R.string.copy_set_aside_body, source)
            }
        }
    val text = if (banner.complete) body else body + "\n" + stringResource(R.string.copy_fragments)
    Callout(title, text, if (banner.complete) Tone.Info else Tone.Caution, modifier) {
        BannerButton(R.string.save_as) { onAction(EditorAction.SaveAsFromBanner) }
        BannerButton(R.string.recovered_got_it) { onAction(EditorAction.DismissBanner) }
    }
}
