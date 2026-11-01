@file:Suppress("TooManyFunctions") // The Storage board's sections, one small composable each.

package com.scorchsoft.ssmarkdown.storage

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.files.storage.StorageCategory
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.FileUsage
import com.scorchsoft.ssmarkdown.ui.AppIcons
import com.scorchsoft.ssmarkdown.ui.InfoNote
import com.scorchsoft.ssmarkdown.ui.LocalNow
import com.scorchsoft.ssmarkdown.ui.NoteText
import com.scorchsoft.ssmarkdown.ui.RelativeTime
import com.scorchsoft.ssmarkdown.ui.SecondaryScreen
import com.scorchsoft.ssmarkdown.ui.SectionLabel
import com.scorchsoft.ssmarkdown.ui.SettingRow
import com.scorchsoft.ssmarkdown.ui.SwitchRow
import com.scorchsoft.ssmarkdown.ui.relativeTimeText

/** Formats bytes as the phone does ("48.2 MB"). */
@Composable
fun bytesText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/**
 * Storage (the Storage board): the breakdown, Free up space (each clear
 * behind a confirmation), History by file, the Automatic limits, Recent
 * clean-ups and the note about Android's own buttons.
 */
@Composable
fun StorageScreen(
    state: StorageUiState,
    onAction: (StorageAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryScreen(
        stringResource(R.string.storage_title),
        { onAction(StorageAction.Back) },
        modifier,
        actions = { StorageMenu(onAction) },
    ) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = SS.colors.inkMuted)
        Breakdown(state)
        if (state.lowStorage) NoteText(stringResource(R.string.storage_low))
        FreeUpSpace(state, onAction)
        HistoryByFile(state, onAction)
        if (state.setAside.isNotEmpty()) SetAside(state, onAction)
        Automatic(state, onAction)
        InfoNote(stringResource(R.string.storage_android_title), stringResource(R.string.storage_android_body))
    }
    StorageDialogs(state, onAction)
}

/**
 * ⋮ with Import history (.zip), as the spec places it (UX2-16): one rare
 * action does not earn an always-visible label on an otherwise calm screen.
 */
@Composable
private fun StorageMenu(onAction: (StorageAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(AppIcons.more, stringResource(R.string.more), tint = SS.colors.ink)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SS.colors.chrome) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.storage_import), style = SS.type.body, color = SS.colors.ink) },
                onClick = {
                    open = false
                    onAction(StorageAction.ImportHistory)
                },
            )
        }
    }
}

@Composable
private fun Breakdown(state: StorageUiState) {
    val breakdown = state.breakdown
    Text(
        breakdown?.let { stringResource(R.string.storage_used, bytesText(it.totalBytes)) }
            ?: stringResource(R.string.storage_measuring),
        style = SS.type.title,
        color = SS.colors.ink,
        modifier = Modifier.padding(16.dp),
    )
    breakdown ?: return
    val total = breakdown.totalBytes.coerceAtLeast(1)
    Row(
        Modifier
            .padding(
                horizontal = 16.dp,
            ).fillMaxWidth()
            .height(8.dp)
            .background(SS.colors.segmentTrack, RoundedCornerShape(4.dp)),
    ) {
        StorageCategory.entries.forEach { category ->
            val bytes = breakdown.usage[category]?.bytes ?: 0
            if (bytes > 0) Box(Modifier.weight(bytes.toFloat() / total).height(8.dp).background(barColour(category)))
        }
    }
    StorageCategory.entries.forEach { category ->
        val usage = breakdown.usage[category] ?: return@forEach
        val label = stringResource(categoryLabel(category))
        val title = if (category in COUNTED) stringResource(R.string.storage_with_count, label, usage.items) else label
        SettingRow(title, value = bytesText(usage.bytes))
    }
}

private val COUNTED = setOf(StorageCategory.VersionHistory, StorageCategory.Fonts, StorageCategory.Drafts)

@Composable
private fun barColour(category: StorageCategory) =
    when (category) {
        StorageCategory.VersionHistory -> SS.colors.ink
        StorageCategory.App -> SS.colors.gutter
        else -> SS.colors.inkMuted
    }

private fun categoryLabel(category: StorageCategory): Int =
    when (category) {
        StorageCategory.VersionHistory -> R.string.storage_history
        StorageCategory.Fonts -> R.string.storage_fonts
        StorageCategory.PreviewCache -> R.string.storage_cache
        StorageCategory.Drafts -> R.string.storage_drafts
        StorageCategory.RecoveryCopies -> R.string.storage_recovery
        StorageCategory.App -> R.string.storage_app
    }

@Composable
private fun FreeUpSpace(
    state: StorageUiState,
    onAction: (StorageAction) -> Unit,
) {
    SectionLabel(stringResource(R.string.storage_free_up))
    val notOpened = state.notOpenedPreview
    SettingRow(
        stringResource(R.string.storage_not_opened),
        notOpened?.let {
            pluralStringResource(
                R.plurals.storage_not_opened_detail,
                it.files,
                it.files,
                bytesText(it.bytes),
            )
        },
    ) {
        PickerButton(daysText(state.notOpenedDays)) { onAction(StorageAction.OpenPicker(StoragePicker.NotOpened)) }
        ClearButton(R.string.storage_clear) { onAction(StorageAction.AskClear(ClearKind.NotOpened)) }
    }
    SettingRow(
        stringResource(R.string.storage_older),
        state.olderPreview?.let { stringResource(R.string.storage_older_detail, bytesText(it.bytes)) },
    ) {
        PickerButton(daysText(state.olderThanDays)) { onAction(StorageAction.OpenPicker(StoragePicker.OlderThan)) }
        ClearButton(R.string.storage_clear) { onAction(StorageAction.AskClear(ClearKind.OlderThan)) }
    }
    SettingRow(stringResource(R.string.storage_cache), stringResource(R.string.storage_cache_detail)) {
        ClearButton(R.string.storage_clear) { onAction(StorageAction.AskClear(ClearKind.Cache)) }
    }
    SettingRow(stringResource(R.string.storage_all), stringResource(R.string.storage_all_detail)) {
        ClearButton(R.string.storage_clear_all) { onAction(StorageAction.AskClear(ClearKind.All)) }
    }
    NoteText(stringResource(R.string.storage_never_note))
}

@Composable
private fun HistoryByFile(
    state: StorageUiState,
    onAction: (StorageAction) -> Unit,
) {
    SectionLabel(stringResource(R.string.storage_by_file))
    val other = if (state.sort == StorageSort.Largest) StorageSort.LastOpened else StorageSort.Largest
    TextButton(onClick = { onAction(StorageAction.SetSort(other)) }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(
            stringResource(
                if (state.sort ==
                    StorageSort.Largest
                ) {
                    R.string.storage_sort_size
                } else {
                    R.string.storage_sort_opened
                },
            ) +
                " ▾",
            style = SS.type.label,
            color = SS.colors.ink,
        )
    }
    state.sortedFiles.forEach { FileRow(it, state.fileMenu == it.document.uri, onAction) }
}

/** Edits set aside into quarantine: never deleted on their own, and always one tap from a copy (R2-DS-3). */
@Composable
private fun SetAside(
    state: StorageUiState,
    onAction: (StorageAction) -> Unit,
) {
    SectionLabel(stringResource(R.string.storage_set_aside))
    NoteText(stringResource(R.string.storage_set_aside_note))
    state.setAside.forEachIndexed { index, edits ->
        val name = edits.uri?.let { DocumentKey(it.value).displayName } ?: stringResource(R.string.copy_unknown_file)
        val time = relativeTimeText(RelativeTime.between(edits.setAsideAtMillis, LocalNow.current))
        SettingRow(stringResource(R.string.storage_set_aside_row, name, time), value = bytesText(edits.sizeBytes)) {
            TextButton(onClick = { onAction(StorageAction.OpenSetAside(index)) }) {
                Text(stringResource(R.string.storage_set_aside_open), style = SS.type.label, color = SS.colors.ink)
            }
        }
    }
}

@Composable
private fun FileRow(
    file: FileUsage,
    menuOpen: Boolean,
    onAction: (StorageAction) -> Unit,
) {
    val opened = relativeTimeText(RelativeTime.between(file.lastOpenedMillis, LocalNow.current))
    val size = bytesText(file.bytes)
    val detail =
        if (file.pinned > 0) {
            pluralStringResource(
                R.plurals.storage_file_detail_pins,
                file.versions,
                size,
                file.versions,
                file.pinned,
                opened,
            )
        } else {
            pluralStringResource(R.plurals.storage_file_detail, file.versions, size, file.versions, opened)
        }
    val uri = file.document.uri
    SettingRow(file.document.displayName, detail) {
        Box {
            IconButton(onClick = { onAction(StorageAction.FileMenu(uri)) }) {
                Icon(
                    AppIcons.more,
                    stringResource(R.string.storage_file_actions, file.document.displayName),
                    tint = SS.colors.inkMuted,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = {
                onAction(StorageAction.FileMenu(null))
            }, containerColor = SS.colors.chrome) {
                MenuEntry(R.string.storage_export_history) { onAction(StorageAction.ExportFile(uri)) }
                MenuEntry(R.string.storage_clear_history) { onAction(StorageAction.AskClear(ClearKind.OneFile, uri)) }
                MenuEntry(R.string.storage_open_history) { onAction(StorageAction.OpenHistory(uri)) }
            }
        }
    }
}

@Composable
private fun Automatic(
    state: StorageUiState,
    onAction: (StorageAction) -> Unit,
) {
    SectionLabel(stringResource(R.string.storage_automatic))
    SwitchRow(
        stringResource(R.string.storage_keep_history),
        state.policy.recording,
        { onAction(StorageAction.ToggleKeepHistory) },
        // The subtitle describes the state the switch is in (UX2-16).
        stringResource(
            if (state.policy.recording) R.string.storage_keep_history_on_hint else R.string.storage_keep_history_hint,
        ),
    )
    SettingRow(stringResource(R.string.storage_limit), value = limitText(state.limitBytes), onClick = {
        onAction(StorageAction.OpenPicker(StoragePicker.Limit))
    })
    SettingRow(
        stringResource(R.string.storage_forget),
        value = state.policy.forgetUnusedAfterDays?.let { daysText(it) } ?: stringResource(R.string.storage_never),
        onClick = { onAction(StorageAction.OpenPicker(StoragePicker.Forget)) },
    )
    SettingRow(
        stringResource(R.string.storage_recent_cleanups),
        value =
            if (state.cleanups.isEmpty()) {
                stringResource(
                    R.string.storage_none_yet,
                )
            } else {
                state.cleanups.size.toString()
            },
        onClick = { onAction(StorageAction.ToggleCleanups) },
    )
    if (state.showCleanups) state.cleanups.take(MAX_CLEANUP_ROWS).forEach { CleanupRow(it) }
}

private const val MAX_CLEANUP_ROWS = 50
private const val MONTH_DAYS = 30
private const val HALF_YEAR_DAYS = 180
private const val HALF_YEAR_MONTHS = 6
private const val YEAR_DAYS = 365

/** "1 GB", or "No limit". */
@Composable
fun limitText(bytes: Long?): String = bytes?.let { bytesText(it) } ?: stringResource(R.string.storage_no_limit)

/** "30 days", "6 months", "1 year". */
@Composable
fun daysText(days: Int): String =
    when (days) {
        MONTH_DAYS -> stringResource(R.string.storage_month)
        HALF_YEAR_DAYS -> pluralStringResource(R.plurals.storage_months, HALF_YEAR_MONTHS, HALF_YEAR_MONTHS)
        YEAR_DAYS -> stringResource(R.string.storage_year)
        else -> pluralStringResource(R.plurals.storage_days, days, days)
    }

@Composable
private fun PickerButton(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) { Text("$label ▾", style = SS.type.label, color = SS.colors.ink) }
}

@Composable
private fun ClearButton(
    label: Int,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) { Text(stringResource(label), style = SS.type.label, color = SS.colors.ink) }
}

@Composable
internal fun MenuEntry(
    label: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label), style = SS.type.body, color = SS.colors.ink) },
        onClick = onClick,
    )
}
