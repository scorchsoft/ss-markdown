package com.scorchsoft.ssmarkdown.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorBanner
import com.scorchsoft.ssmarkdown.ui.AppIcons
import java.text.DateFormat
import java.util.Date

private val BannerShape = RoundedCornerShape(12.dp)

/**
 * The one banner under the header. Callouts are a tinted box with an icon
 * and label, never a one-sided accent border (quality-bar §2). Info blue
 * for recovery, caution amber for outside changes, needs-you red text
 * only for a failed save.
 */
@Composable
fun EditorBannerView(
    banner: EditorBanner,
    fileName: String,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (banner) {
        is EditorBanner.Recovered -> {
            RecoveredBanner(banner, onAction, modifier)
        }

        EditorBanner.ChangedOutside -> {
            Callout(stringResource(R.string.changed_outside_banner, fileName), null, Tone.Caution, modifier) {
                BannerButton(R.string.review) { onAction(EditorAction.ReviewConflict) }
            }
        }

        is EditorBanner.SaveFailed -> {
            Callout(stringResource(R.string.save_failed_banner), null, Tone.Error, modifier) {
                BannerButton(R.string.save_as) { onAction(EditorAction.SaveAsFromBanner) }
                BannerButton(R.string.details) { onAction(EditorAction.ShowFailureDetails) }
            }
        }

        EditorBanner.FileMissing -> {
            Callout(stringResource(R.string.file_missing_banner, fileName), null, Tone.Caution, modifier) {
                BannerButton(R.string.save_as) { onAction(EditorAction.SaveAsFromBanner) }
                BannerButton(R.string.recreate) { onAction(EditorAction.RecreateFile) }
            }
        }

        is EditorBanner.SaveAllResult -> {
            SaveAllBanner(banner, onAction, modifier)
        }

        EditorBanner.EditsKeptAside -> {
            KeptAsideBanner(onAction, modifier)
        }

        is EditorBanner.RecoveredCopy -> {
            RecoveredCopyBanner(banner, onAction, modifier)
        }

        EditorBanner.RecoveryCopiesStopped -> {
            val body = stringResource(R.string.recovery_stopped_body)
            Callout(stringResource(R.string.recovery_stopped_title), body, Tone.Error, modifier) {
                BannerButton(R.string.save_as) { onAction(EditorAction.SaveAsFromBanner) }
                BannerButton(R.string.dismiss) { onAction(EditorAction.DismissBanner) }
            }
        }
    }
}

@Composable
private fun RecoveredBanner(
    banner: EditorBanner.Recovered,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier,
) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(banner.atMillis))
    val body =
        stringResource(if (banner.closedBySwipe) R.string.recovered_body_swipe else R.string.recovered_body, time)
    Callout(stringResource(R.string.recovered_title), body, Tone.Info, modifier) {
        BannerButton(R.string.recovered_discard) { onAction(EditorAction.RecoveryDiscard) }
        BannerButton(R.string.recovered_compare) { onAction(EditorAction.RecoveryCompare) }
        BannerButton(R.string.recovered_got_it) { onAction(EditorAction.RecoveryGotIt) }
    }
}

@Composable
private fun SaveAllBanner(
    banner: EditorBanner.SaveAllResult,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier,
) {
    val title = pluralStringResource(R.plurals.saved_files, banner.saved, banner.saved)
    val lines = banner.skipped.map { stringResource(R.string.save_all_skipped, it) }
    val body = lines.joinToString("\n").ifEmpty { null }
    Callout(title, body, if (banner.skipped.isEmpty()) Tone.Info else Tone.Caution, modifier) {
        if (banner.skipped.isNotEmpty()) BannerButton(R.string.review) { onAction(EditorAction.ReviewConflict) }
        BannerButton(R.string.dismiss) { onAction(EditorAction.DismissBanner) }
    }
}

internal enum class Tone { Info, Caution, Error }

@Composable
internal fun Callout(
    title: String,
    body: String?,
    tone: Tone,
    modifier: Modifier,
    actions: @Composable () -> Unit,
) {
    val colors = SS.colors
    val (fg, bg) =
        when (tone) {
            Tone.Info -> colors.info to colors.infoBg
            Tone.Caution -> colors.caution to colors.cautionBg
            Tone.Error -> colors.needsYou to colors.segmentTrack
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(bg, BannerShape)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(AppIcons.info, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = SS.type.bodyStrong, color = if (tone == Tone.Error) colors.ink else fg)
                body?.let { Text(it, style = SS.type.meta, color = colors.ink) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
    }
}

@Composable
internal fun BannerButton(
    label: Int,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) { Text(stringResource(label), style = SS.type.label, color = SS.colors.ink) }
}
