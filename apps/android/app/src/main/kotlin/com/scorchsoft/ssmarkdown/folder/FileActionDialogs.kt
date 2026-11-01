package com.scorchsoft.ssmarkdown.folder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS

/**
 * Rename · Duplicate · Delete for a file row (long-press, or its ⋯).
 * Delete asks first and keeps the history entry. The rename field keeps
 * its text across rotation (robustness §5a, rule 5).
 */
@Composable
fun FileActionDialogs(
    action: FileAction?,
    onAction: (FolderAction) -> Unit,
) {
    val dismiss = { onAction(FolderAction.DismissAction) }
    when (action) {
        null -> {
            Unit
        }

        is FileAction.Menu -> {
            AlertDialog(
                onDismissRequest = dismiss,
                containerColor = SS.colors.chrome,
                title = { Text(action.name, style = SS.type.title, color = SS.colors.ink) },
                text = {
                    Column {
                        MenuRow(R.string.file_rename) { onAction(FolderAction.StartRename(action.uri)) }
                        MenuRow(R.string.file_duplicate) { onAction(FolderAction.Duplicate(action.uri)) }
                        MenuRow(R.string.file_delete) { onAction(FolderAction.AskDelete(action.uri)) }
                    }
                },
                confirmButton = { DialogButton(R.string.cancel, dismiss) },
            )
        }

        is FileAction.Rename -> {
            RenameDialog(action, onAction)
        }

        is FileAction.ConfirmDelete -> {
            AlertDialog(
                onDismissRequest = dismiss,
                containerColor = SS.colors.chrome,
                title = {
                    Text(
                        stringResource(R.string.file_delete_title, action.name),
                        style = SS.type.title,
                        color = SS.colors.ink,
                    )
                },
                text = { Text(stringResource(R.string.file_delete_body), style = SS.type.body, color = SS.colors.ink) },
                confirmButton = {
                    TextButton(onClick = { onAction(FolderAction.Delete(action.uri)) }) {
                        Text(
                            stringResource(R.string.file_delete),
                            style = SS.type.label,
                            color = SS.colors.needsYouText,
                        )
                    }
                },
                dismissButton = { DialogButton(R.string.cancel, dismiss) },
            )
        }
    }
}

@Composable
private fun RenameDialog(
    action: FileAction.Rename,
    onAction: (FolderAction) -> Unit,
) {
    var name by rememberSaveable(action.uri) { mutableStateOf(action.name) }
    AlertDialog(
        onDismissRequest = { onAction(FolderAction.DismissAction) },
        containerColor = SS.colors.chrome,
        title = { Text(stringResource(R.string.file_rename), style = SS.type.title, color = SS.colors.ink) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.file_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onAction(FolderAction.Rename(action.uri, name.trim())) },
            ) {
                Text(stringResource(R.string.file_rename), style = SS.type.label, color = SS.colors.ink)
            }
        },
        dismissButton = { DialogButton(R.string.cancel) { onAction(FolderAction.DismissAction) } },
    )
}

@Composable
private fun MenuRow(
    label: Int,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = SS.type.body, color = SS.colors.ink, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DialogButton(
    label: Int,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) { Text(stringResource(label), style = SS.type.label, color = SS.colors.ink) }
}
