@file:Suppress("TooManyFunctions") // Every Storage dialog, one small composable each.

package com.scorchsoft.ssmarkdown.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.history.versions.CleanupEntry
import com.scorchsoft.ssmarkdown.history.versions.CleanupReason
import com.scorchsoft.ssmarkdown.history.versions.StorageLimit
import com.scorchsoft.ssmarkdown.settings.HistoryLimits
import java.text.DateFormat
import java.util.Date

/** Every dialog the Storage screen can show, from its state. */
@Composable
fun StorageDialogs(
    state: StorageUiState,
    onAction: (StorageAction) -> Unit,
) {
    state.confirm?.let { ConfirmClearDialog(it, onAction) }
    when (state.turnOff) {
        TurnOffStep.Choose -> TurnOffDialog(onAction)
        TurnOffStep.ConfirmDelete -> ConfirmTurnOffDelete(onAction)
        null -> Unit
    }
    state.picker?.let { PickerDialog(it, state, onAction) }
    state.message?.let { ResultDialog(it, onAction) }
}

/**
 * StorageConfirm: exactly what will be deleted, "This can't be undone."
 * highlighted, what is kept, and Export first ticked by default, so the
 * button reads *Export and clear*; unticking makes it *Clear permanently*.
 */
@Composable
fun ConfirmClearDialog(
    confirm: StorageConfirm,
    onAction: (StorageAction) -> Unit,
) {
    val label =
        when {
            confirm.kind == ClearKind.Cache -> R.string.storage_clear
            confirm.exportFirst -> R.string.confirm_export_and_clear
            else -> R.string.confirm_clear_permanently
        }
    AlertDialog(
        onDismissRequest = { onAction(StorageAction.CancelConfirm) },
        containerColor = SS.colors.chrome,
        title = { Text(confirmTitle(confirm), style = SS.type.title, color = SS.colors.ink) },
        text = { ConfirmBody(confirm, onAction) },
        confirmButton = {
            TextButton(onClick = { onAction(StorageAction.ConfirmClear) }) {
                Text(stringResource(label), style = SS.type.label, color = SS.colors.needsYou)
            }
        },
        dismissButton = { Cancel(onAction) },
    )
}

@Composable
private fun ConfirmBody(
    confirm: StorageConfirm,
    onAction: (StorageAction) -> Unit,
) {
    Column {
        if (confirm.kind == ClearKind.Cache) {
            Text(stringResource(R.string.confirm_cache_body), style = SS.type.body, color = SS.colors.ink)
            return@Column
        }
        val body =
            pluralStringResource(
                R.plurals.confirm_body,
                confirm.versions,
                confirm.versions,
                bytesText(confirm.bytes),
                confirm.files,
            )
        Text(body, style = SS.type.body, color = SS.colors.ink)
        CantUndo()
        val kept = if (confirm.includePinned) R.string.confirm_kept_all_pins else R.string.confirm_kept
        Text(stringResource(kept), style = SS.type.meta, color = SS.colors.inkMuted)
        if (confirm.kind == ClearKind.All || confirm.kind == ClearKind.OneFile) {
            CheckRow(stringResource(R.string.confirm_include_pinned), confirm.includePinned) {
                onAction(StorageAction.ToggleIncludePinned)
            }
        }
        CheckRow(stringResource(R.string.confirm_export_first), confirm.exportFirst) {
            onAction(StorageAction.ToggleExportFirst)
        }
    }
}

/** "This can't be undone.", highlighted as a caution, never red. */
@Composable
private fun CantUndo() {
    Text(
        stringResource(R.string.confirm_cant_undo),
        style = SS.type.body.copy(fontWeight = FontWeight.SemiBold),
        color = SS.colors.caution,
        modifier =
            Modifier
                .padding(
                    vertical = 6.dp,
                ).background(SS.colors.cautionBg, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp),
    )
}

@Composable
private fun confirmTitle(confirm: StorageConfirm): String =
    when (confirm.kind) {
        ClearKind.NotOpened -> {
            pluralStringResource(R.plurals.confirm_title_files, confirm.files, confirm.files)
        }

        ClearKind.OlderThan, ClearKind.OneFile -> {
            pluralStringResource(
                R.plurals.confirm_title_versions,
                confirm.versions,
                confirm.versions,
            )
        }

        ClearKind.All -> {
            stringResource(R.string.confirm_title_all)
        }

        ClearKind.Cache -> {
            stringResource(R.string.confirm_title_cache)
        }
    }

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onToggle).padding(vertical = 4.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors =
                CheckboxDefaults.colors(
                    checkedColor = SS.colors.ink,
                    checkmarkColor = SS.colors.bg,
                    uncheckedColor = SS.colors.inkMuted,
                ),
        )
        Text(label, style = SS.type.body, color = SS.colors.ink, modifier = Modifier.padding(start = 8.dp))
    }
}

/** "Keep version history" off: keep it, export then delete, or delete (which asks again). */
@Composable
private fun TurnOffDialog(onAction: (StorageAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(StorageAction.CancelConfirm) },
        containerColor = SS.colors.chrome,
        title = { Text(stringResource(R.string.turn_off_title), style = SS.type.title, color = SS.colors.ink) },
        text = {
            Column {
                Text(stringResource(R.string.turn_off_body), style = SS.type.body, color = SS.colors.ink)
                ChoiceRow(R.string.turn_off_keep) { onAction(StorageAction.TurnOffKeep) }
                ChoiceRow(R.string.turn_off_export_delete) { onAction(StorageAction.TurnOffExportThenDelete) }
                ChoiceRow(R.string.turn_off_delete) { onAction(StorageAction.TurnOffAskDelete) }
            }
        },
        confirmButton = { Cancel(onAction) },
    )
}

@Composable
private fun ConfirmTurnOffDelete(onAction: (StorageAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(StorageAction.CancelConfirm) },
        containerColor = SS.colors.chrome,
        title = { Text(stringResource(R.string.confirm_title_all), style = SS.type.title, color = SS.colors.ink) },
        text = {
            Column {
                Text(
                    stringResource(R.string.confirm_cant_undo),
                    style = SS.type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = SS.colors.caution,
                )
                Text(stringResource(R.string.confirm_kept_all_pins), style = SS.type.meta, color = SS.colors.inkMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(StorageAction.TurnOffConfirmDelete) }) {
                Text(
                    stringResource(R.string.confirm_clear_permanently),
                    style = SS.type.label,
                    color = SS.colors.needsYou,
                )
            }
        },
        dismissButton = { Cancel(onAction) },
    )
}

@Composable
private fun PickerDialog(
    picker: StoragePicker,
    state: StorageUiState,
    onAction: (StorageAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(StorageAction.DismissPicker) },
        containerColor = SS.colors.chrome,
        text = {
            Column {
                when (picker) {
                    StoragePicker.NotOpened -> {
                        StorageUiState.NOT_OPENED_CHOICES.forEach { d ->
                            Choice(daysText(d), d == state.notOpenedDays) { onAction(StorageAction.ChooseNotOpened(d)) }
                        }
                    }

                    StoragePicker.OlderThan -> {
                        StorageUiState.OLDER_THAN_CHOICES.forEach { d ->
                            Choice(daysText(d), d == state.olderThanDays) { onAction(StorageAction.ChooseOlderThan(d)) }
                        }
                    }

                    StoragePicker.Limit -> {
                        HistoryLimits.total.forEach { l ->
                            Choice(limitText((l as? StorageLimit.Bytes)?.bytes), l == state.policy.totalLimit) {
                                onAction(StorageAction.SetLimit(l))
                            }
                        }
                    }

                    StoragePicker.Forget -> {
                        HistoryLimits.forgetAfterDays.forEach { d ->
                            Choice(
                                d?.let { daysText(it) } ?: stringResource(R.string.storage_never),
                                d == state.policy.forgetUnusedAfterDays,
                            ) {
                                onAction(StorageAction.SetForget(d))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAction(StorageAction.DismissPicker)
            }) { Text(stringResource(R.string.cancel), color = SS.colors.ink) }
        },
    )
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            (if (selected) "✓ " else "") + label,
            style = if (selected) SS.type.bodyStrong else SS.type.body,
            color = SS.colors.ink,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChoiceRow(
    label: Int,
    onClick: () -> Unit,
) = Choice(stringResource(label), selected = false, onClick)

@Composable
private fun ResultDialog(
    message: StorageMessage,
    onAction: (StorageAction) -> Unit,
) {
    val text =
        when (message) {
            is StorageMessage.Cleared -> {
                pluralStringResource(
                    R.plurals.cleared_result,
                    message.versions,
                    message.versions,
                    bytesText(message.bytes),
                )
            }

            is StorageMessage.Imported -> {
                pluralStringResource(R.plurals.imported_result, message.versions, message.versions)
            }

            StorageMessage.ExportReady -> {
                stringResource(R.string.export_saved)
            }

            StorageMessage.Failed -> {
                stringResource(R.string.msg_failed)
            }
        }
    AlertDialog(
        onDismissRequest = { onAction(StorageAction.DismissMessage) },
        containerColor = SS.colors.chrome,
        text = { Text(text, style = SS.type.body, color = SS.colors.ink) },
        confirmButton = {
            TextButton(onClick = {
                onAction(StorageAction.DismissMessage)
            }) { Text(stringResource(R.string.ok), color = SS.colors.ink) }
        },
    )
}

@Composable
private fun Cancel(onAction: (StorageAction) -> Unit) {
    TextButton(onClick = {
        onAction(StorageAction.CancelConfirm)
    }) { Text(stringResource(R.string.cancel), style = SS.type.label, color = SS.colors.ink) }
}

/** One row of Recent clean-ups: "3 Nov · thinned 34 versions of handbook.md". */
@Composable
fun CleanupRow(entry: CleanupEntry) {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.timeMillis))
    val what =
        stringResource(
            when (entry.reason) {
                CleanupReason.Thinned -> R.string.cleanup_thinned
                CleanupReason.FileLimit, CleanupReason.TotalLimit -> R.string.cleanup_limit
                CleanupReason.UnusedFile -> R.string.cleanup_unused
                CleanupReason.Damaged -> R.string.cleanup_damaged
                CleanupReason.Cleared -> R.string.cleanup_cleared
                CleanupReason.HistoryOff -> R.string.cleanup_history_off
            },
        )
    Text(
        pluralStringResource(
            R.plurals.cleanup_row,
            entry.versionsRemoved,
            date,
            what,
            entry.versionsRemoved,
            entry.document.displayName,
        ),
        style = SS.type.meta,
        color = SS.colors.inkMuted,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
