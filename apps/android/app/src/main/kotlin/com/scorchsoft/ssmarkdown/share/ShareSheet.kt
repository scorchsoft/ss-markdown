package com.scorchsoft.ssmarkdown.share

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.core.ui.components.SSSegmentedControl
import com.scorchsoft.ssmarkdown.settings.AppendPosition
import com.scorchsoft.ssmarkdown.settings.AppendPrefix
import com.scorchsoft.ssmarkdown.settings.LocationLabel

private val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val QuoteShape = RoundedCornerShape(10.dp)
private const val SCRIM_ALPHA = 0.32f
private const val QUOTE_LINES = 4

/**
 * The share-in sheet over the sending app (the ShareIn board): the quote,
 * Append to (recent and pinned files, newest first) · New note · Another
 * file…, Bottom / Top, the prefix, and Cancel / Append (or Create note).
 * Once done, the sheet itself confirms "Added to <file> · Open".
 */
@Composable
fun ShareSheet(
    state: ShareUiState,
    onAction: (ShareAction) -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)).clickable {
            onAction(ShareAction.Cancel)
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(SS.colors.chrome, SheetShape)
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(stringResource(R.string.share_title), style = SS.type.title, color = SS.colors.ink)
            Quote(state)
            when (val phase = state.phase) {
                SharePhase.Choosing, SharePhase.Working -> Choosing(state, onAction)
                else -> Outcome(phase, onAction)
            }
        }
    }
}

@Composable
private fun Quote(state: ShareUiState) {
    Column(
        Modifier
            .padding(vertical = 12.dp)
            .fillMaxWidth()
            .background(SS.colors.bg, QuoteShape)
            .padding(12.dp),
    ) {
        Text(
            "“${state.text.trim()}”",
            style = SS.type.body,
            color = SS.colors.ink,
            maxLines = QUOTE_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            state.source?.let { pluralStringResource(R.plurals.share_from, state.text.length, it, state.text.length) }
                ?: pluralStringResource(R.plurals.share_characters, state.text.length, state.text.length),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
        )
    }
}

@Composable
private fun Choosing(
    state: ShareUiState,
    onAction: (ShareAction) -> Unit,
) {
    Text(stringResource(R.string.share_append_to), style = SS.type.label, color = SS.colors.inkMuted)
    val lastUsed = stringResource(R.string.share_pinned_last)
    val recent = stringResource(R.string.share_recent)
    state.targets.forEachIndexed { index, file ->
        val selected = state.target == file.uri
        val kind = if (index == 0 && selected) lastUsed else recent
        val where = file.location.ifEmpty { LocationLabel.of(file.uri) }
        val detail = if (where.isEmpty()) kind else "$kind · $where"
        TargetRow(file.name, detail, selected) { onAction(ShareAction.ChooseTarget(file.uri)) }
    }
    TargetRow(stringResource(R.string.share_new_note), null, state.target == null) {
        onAction(ShareAction.ChooseTarget(null))
    }
    TextButton(onClick = { onAction(ShareAction.AnotherFile) }) {
        Text(stringResource(R.string.share_another), style = SS.type.body, color = SS.colors.ink)
    }
    if (state.target != null) AppendOptions(state, onAction)
    Buttons(state, onAction)
}

@Composable
private fun AppendOptions(
    state: ShareUiState,
    onAction: (ShareAction) -> Unit,
) {
    SSSegmentedControl(
        options = listOf(stringResource(R.string.share_bottom), stringResource(R.string.share_top)),
        selectedIndex = state.position.ordinal,
        onSelect = { onAction(ShareAction.SetPosition(AppendPosition.entries[it])) },
        modifier = Modifier.padding(vertical = 8.dp),
    )
    val prefix = if (state.prefix == AppendPrefix.None) R.string.share_prefix_none else R.string.share_prefix_date
    TextButton(onClick = { onAction(ShareAction.TogglePrefix) }) {
        Text(stringResource(prefix), style = SS.type.label, color = SS.colors.ink)
    }
}

@Composable
private fun Buttons(
    state: ShareUiState,
    onAction: (ShareAction) -> Unit,
) {
    // The label follows the choice: "Append" or "Create note" (review 3).
    val confirm = if (state.target == null) R.string.share_create else R.string.share_append
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { onAction(ShareAction.Cancel) }) {
            Text(stringResource(R.string.cancel), style = SS.type.label, color = SS.colors.ink)
        }
        TextButton(
            enabled = state.phase == SharePhase.Choosing,
            onClick = { onAction(ShareAction.Confirm) },
            modifier = Modifier.background(SS.colors.ink, RoundedCornerShape(20.dp)),
        ) {
            Text(stringResource(confirm), style = SS.type.label, color = SS.colors.bg)
        }
    }
}

@Composable
private fun TargetRow(
    name: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = SS.dimens.rowPrimary,
                ).selectable(selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = SS.colors.ink),
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(name, style = SS.type.body, color = SS.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = SS.type.meta, color = SS.colors.inkMuted) }
        }
    }
}

@Composable
private fun Outcome(
    phase: SharePhase,
    onAction: (ShareAction) -> Unit,
) {
    val context = LocalContext.current
    val untitled = stringResource(R.string.untitled)
    val text =
        when (phase) {
            is SharePhase.Done -> {
                stringResource(R.string.share_added, phase.name.ifEmpty { untitled })
            }

            is SharePhase.Blocked -> {
                stringResource(if (phase.conflict) R.string.share_conflict else R.string.share_read_only, phase.name)
            }

            is SharePhase.Failed -> {
                // A named target that failed (missing, too large, the write failed) offers New note.
                if (phase.name.isEmpty()) {
                    stringResource(R.string.share_failed, untitled)
                } else {
                    stringResource(R.string.share_failed_target, phase.name)
                }
            }

            is SharePhase.Unusable -> {
                phase.tooLargeBytes?.let {
                    stringResource(
                        R.string.share_too_large,
                        Formatter.formatShortFileSize(context, it),
                    )
                }
                    ?: stringResource(R.string.share_not_text)
            }

            else -> {
                ""
            }
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth().semantics {
                liveRegion =
                    LiveRegionMode.Polite
            },
    ) {
        Text(text, style = SS.type.body, color = SS.colors.ink, modifier = Modifier.weight(1f))
        OutcomeAction(phase, onAction)
    }
}

/** The outcome's one button: Open after a success, New note after a refused or failed target, else Cancel. */
@Composable
private fun OutcomeAction(
    phase: SharePhase,
    onAction: (ShareAction) -> Unit,
) {
    val (label, action) =
        when {
            phase is SharePhase.Done -> {
                R.string.share_open to ShareAction.OpenResult
            }

            phase is SharePhase.Blocked -> {
                R.string.share_new_note to ShareAction.ChooseTarget(null)
            }

            phase is SharePhase.Failed && phase.name.isNotEmpty() -> {
                R.string.share_new_note to
                    ShareAction.ChooseTarget(null)
            }

            else -> {
                R.string.cancel to ShareAction.Cancel
            }
        }
    TextButton(onClick = { onAction(action) }) { Text(stringResource(label), color = SS.colors.ink) }
}
