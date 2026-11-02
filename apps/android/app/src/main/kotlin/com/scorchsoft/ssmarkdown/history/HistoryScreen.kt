@file:Suppress("TooManyFunctions") // The History board's parts, one small composable each.

package com.scorchsoft.ssmarkdown.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.history.versions.DiffLine
import com.scorchsoft.ssmarkdown.history.versions.DiffLineType
import com.scorchsoft.ssmarkdown.history.versions.ExportedDocument
import com.scorchsoft.ssmarkdown.history.versions.VersionInfo
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.storage.MenuEntry
import com.scorchsoft.ssmarkdown.storage.bytesText
import com.scorchsoft.ssmarkdown.ui.AppIcons
import com.scorchsoft.ssmarkdown.ui.NoteText
import com.scorchsoft.ssmarkdown.ui.SecondaryScreen
import com.scorchsoft.ssmarkdown.ui.SectionLabel
import com.scorchsoft.ssmarkdown.ui.SwitchRow
import java.text.DateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/**
 * The time column: wide enough for "10:00 PM" in Inter at 1×, so times
 * align; a longer one (a large font, a 24-hour locale with seconds) pushes
 * the label along rather than breaking "PM" onto its own line.
 */
private val TimeColumn = 72.dp

/**
 * Version history (the History board): the timeline by day, a diff
 * snippet with Compare with now and Restore under the tapped entry,
 * long-press to pin and name, the Autosave-this-file toggle on top, and
 * export / import in the options menu.
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    SecondaryScreen(
        stringResource(R.string.history_title),
        { onAction(HistoryAction.Back) },
        modifier,
        actions = {
            IconButton(onClick = {
                menu = true
            }) { Icon(AppIcons.more, stringResource(R.string.history_options), tint = SS.colors.ink) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = SS.colors.chrome) {
                MenuEntry(R.string.history_export) { onAction(HistoryAction.Export).also { menu = false } }
                MenuEntry(R.string.history_import) { onAction(HistoryAction.Import).also { menu = false } }
            }
        },
    ) {
        Text(
            pluralStringResource(
                R.plurals.history_summary,
                state.versionCount,
                state.fileName.ifEmpty {
                    stringResource(R.string.untitled)
                },
                state.versionCount,
                bytesText(state.bytes),
            ),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
            modifier = Modifier.padding(16.dp),
        )
        if (!state.isDraft) {
            SwitchRow(
                stringResource(R.string.history_autosave_file),
                state.autosaveThisFile == true,
                { onAction(HistoryAction.ToggleAutosave) },
                stringResource(
                    if (state.autosaveThisFile ==
                        true
                    ) {
                        R.string.history_autosave_on
                    } else {
                        R.string.history_autosave_default
                    },
                ),
            )
        }
        if (state.versionDamaged) NoteText(stringResource(R.string.history_version_damaged))
        Body(state, onAction)
        NoteText(stringResource(R.string.history_restore_note))
    }
    state.pinning?.let { PinDialog(it, onAction) }
    if (state.importChoices.isNotEmpty()) ImportChoiceDialog(state.fileName, state.importChoices, onAction)
}

/** "Which file's history is this?": an export with no certain match never attaches by guesswork. */
@Composable
private fun ImportChoiceDialog(
    fileName: String,
    choices: List<ExportedDocument>,
    onAction: (HistoryAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(HistoryAction.CancelImport) },
        containerColor = SS.colors.chrome,
        title = { Text(stringResource(R.string.history_import_which), style = SS.type.title, color = SS.colors.ink) },
        text = {
            Column {
                Text(
                    stringResource(R.string.history_import_which_body, fileName),
                    style = SS.type.meta,
                    color = SS.colors.inkMuted,
                )
                choices.forEach { doc ->
                    TextButton(onClick = { onAction(HistoryAction.ImportAs(doc.uri)) }) {
                        Text(
                            pluralStringResource(
                                R.plurals.history_import_choice,
                                doc.versions,
                                doc.displayName,
                                doc.versions,
                            ),
                            style = SS.type.body,
                            color = SS.colors.ink,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                onAction(HistoryAction.CancelImport)
            }) { Text(stringResource(R.string.cancel), color = SS.colors.ink) }
        },
    )
}

@Composable
private fun Body(
    state: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
) {
    when {
        !state.recording -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.history_off),
                    style = SS.type.body,
                    color = SS.colors.ink,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    onAction(HistoryAction.TurnOn)
                }) { Text(stringResource(R.string.history_turn_on), color = SS.colors.ink) }
            }
        }

        state.unreadable -> {
            NoteText(stringResource(R.string.history_unreadable))
        }

        state.days.isEmpty() && !state.loading -> {
            NoteText(stringResource(R.string.history_empty))
        }

        else -> {
            state.days.forEach { Day(it, state, onAction) }
        }
    }
}

@Composable
private fun Day(
    day: TimelineDay,
    state: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
) {
    SectionLabel(dayText(day.label))
    day.rows.forEach { row ->
        when (row) {
            is TimelineRow.Version -> Entry(row.info, state, onAction)
            is TimelineRow.Collapsed -> CollapsedEntry(row, onAction)
        }
    }
}

@Composable
private fun dayText(label: DayLabel): String =
    when (label) {
        DayLabel.Today -> stringResource(R.string.history_today)
        DayLabel.Yesterday -> stringResource(R.string.history_yesterday)
        is DayLabel.Date -> label.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Entry(
    info: VersionInfo,
    state: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
) {
    val expanded = state.expanded == info.id
    val tint = if (expanded) Modifier.background(SS.colors.segmentTrack) else Modifier
    Column(
        Modifier
            .fillMaxWidth()
            .then(tint)
            .combinedClickable(
                role = Role.Button,
                onLongClick = {
                    onAction(
                        if (info.pinned) HistoryAction.Unpin(info.id) else HistoryAction.StartPin(info),
                    )
                },
                onClick = { onAction(HistoryAction.Expand(info.id)) },
            ).heightIn(min = SS.dimens.rowPrimary)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row {
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(info.wallTimeMillis)),
                style = SS.type.label,
                color = SS.colors.inkMuted,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = TimeColumn).padding(end = 8.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    info.label
                        ?.takeIf {
                            info.pinned
                        }?.let { "“$it”" } ?: kindText(info.kind),
                    style = SS.type.bodyStrong,
                    color = SS.colors.ink,
                )
                Text(
                    if (info.pinned) {
                        stringResource(
                            R.string.history_pinned,
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.history_lines,
                            info.changes.removed,
                            info.changes.added,
                            info.changes.removed,
                        )
                    },
                    style = SS.type.meta,
                    color = SS.colors.inkMuted,
                )
            }
            if (info.pinned) Icon(AppIcons.pin, contentDescription = null, tint = SS.colors.ink)
        }
        if (expanded) Expanded(info, state.diff, onAction)
    }
}

@Composable
private fun Expanded(
    info: VersionInfo,
    diff: List<DiffLine>,
    onAction: (HistoryAction) -> Unit,
) {
    Column(
        Modifier
            .padding(
                start = TimeColumn,
                top = 8.dp,
            ).fillMaxWidth()
            .background(SS.colors.bg, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        diff.forEach { DiffText(it) }
    }
    Row(Modifier.padding(start = TimeColumn)) {
        TextButton(onClick = {
            onAction(HistoryAction.CompareWithNow(info.id))
        }) { Text(stringResource(R.string.history_compare), color = SS.colors.ink) }
        TextButton(onClick = {
            onAction(HistoryAction.Restore(info.id))
        }) { Text(stringResource(R.string.history_restore), color = SS.colors.ink) }
    }
}

/** A diff line: removed text grey with a strike-through, added text info blue. Never red. */
@Composable
fun DiffText(line: DiffLine) {
    val (prefix, color, decoration) =
        when (line.type) {
            DiffLineType.Removed -> Triple("− ", SS.colors.inkMuted, TextDecoration.LineThrough)
            DiffLineType.Added -> Triple("+ ", SS.colors.info, null)
            DiffLineType.Context -> Triple("  ", SS.colors.ink, null)
        }
    Text(
        prefix + line.text.trimEnd('\n'),
        style = SS.type.code.copy(textDecoration = decoration),
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun CollapsedEntry(
    row: TimelineRow.Collapsed,
    onAction: (HistoryAction) -> Unit,
) {
    val first = row.versions.first()
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickableCompat { onAction(HistoryAction.ExpandRun(row.key)) }
            .heightIn(min = SS.dimens.rowPrimary)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(first.wallTimeMillis)),
            style = SS.type.label,
            color = SS.colors.inkMuted,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = TimeColumn).padding(end = 8.dp),
        )
        Column {
            Text(
                pluralStringResource(R.plurals.history_collapsed, row.versions.size, row.versions.size),
                style = SS.type.body,
                color = SS.colors.ink,
            )
            Text(stringResource(R.string.history_expand), style = SS.type.meta, color = SS.colors.inkMuted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) =
    combinedClickable(role = Role.Button, onClick = onClick)

@Composable
private fun kindText(kind: VersionKind): String =
    stringResource(
        when (kind) {
            VersionKind.Opened -> R.string.history_kind_opened
            VersionKind.Saved -> R.string.history_kind_saved
            VersionKind.Autosaved -> R.string.history_kind_autosaved
            VersionKind.Checkpoint -> R.string.history_kind_checkpoint
            VersionKind.ChangedOutside -> R.string.history_kind_outside
            VersionKind.BeforeRestore -> R.string.history_kind_before_restore
            VersionKind.Appended -> R.string.history_kind_appended
        },
    )

@Composable
private fun PinDialog(
    info: VersionInfo,
    onAction: (HistoryAction) -> Unit,
) {
    var name by rememberSaveable(info.id.seq) { mutableStateOf(info.label.orEmpty()) }
    AlertDialog(
        onDismissRequest = { onAction(HistoryAction.CancelPin) },
        containerColor = SS.colors.chrome,
        title = { Text(stringResource(R.string.history_pin_title), style = SS.type.title, color = SS.colors.ink) },
        text = {
            OutlinedTextField(value = name, onValueChange = {
                name = it
            }, label = { Text(stringResource(R.string.history_pin_name)) }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = {
                onAction(HistoryAction.Pin(info.id, name))
            }) { Text(stringResource(R.string.history_pin), color = SS.colors.ink) }
        },
        dismissButton = {
            TextButton(onClick = {
                onAction(HistoryAction.CancelPin)
            }) { Text(stringResource(R.string.cancel), color = SS.colors.ink) }
        },
    )
}
