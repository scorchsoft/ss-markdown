package com.scorchsoft.ssmarkdown.folder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.chrome.UnsavedDot
import com.scorchsoft.ssmarkdown.ui.AppIcons

private val IndentPerDepth = 16.dp
private val SaveAllShape = RoundedCornerShape(20.dp)

/**
 * The folder tree: the modal drawer on phones and the docked panel at
 * ≥ 840 dp (ux-spec "Layouts"). "n unsaved files · Save all" exists only
 * while n > 0; Save all is red because it needs you.
 */
@Composable
fun FolderPanel(
    state: FolderUiState,
    onAction: (FolderAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight().background(SS.colors.chrome)) {
        Header(state)
        OutlinedTextField(
            value = state.filter,
            onValueChange = { onAction(FolderAction.Filter(it)) },
            placeholder = { Text(stringResource(R.string.folder_find), style = SS.type.body) },
            leadingIcon = { Icon(AppIcons.search, contentDescription = null, tint = SS.colors.inkMuted) },
            singleLine = true,
            textStyle = SS.type.body,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SS.colors.ink,
                    unfocusedBorderColor = SS.colors.inputOutline,
                    cursorColor = SS.colors.ink,
                ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        if (state.unsavedCount > 0) SaveAllRow(state.unsavedCount) { onAction(FolderAction.SaveAll) }
        if (state.listFailed) {
            Text(
                stringResource(R.string.folder_list_failed),
                style = SS.type.meta,
                color = SS.colors.needsYouText,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(state.visibleRows, key = { it.uri }) { row -> EntryRow(row, onAction) }
        }
        HorizontalDivider(thickness = SS.dimens.hairline, color = SS.colors.border)
        Footer(onAction)
    }
    FileActionDialogs(state.action, onAction)
}

@Composable
private fun Header(state: FolderUiState) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.folder,
                contentDescription = null,
                tint = SS.colors.ink,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                state.name,
                style = SS.type.bodyStrong,
                color = SS.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            pluralStringResource(R.plurals.folder_files, state.fileCount, state.fileCount),
            style = SS.type.meta,
            color = SS.colors.inkMuted,
        )
    }
}

@Composable
private fun SaveAllRow(
    count: Int,
    onSaveAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.folder_unsaved_files, count, count),
            style = SS.type.body,
            color = SS.colors.ink,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onSaveAll,
            modifier = Modifier.background(SS.colors.needsYou, SaveAllShape).heightIn(min = SS.dimens.pill),
        ) {
            Text(stringResource(R.string.folder_save_all), style = SS.type.label, color = SS.colors.onNeedsYou)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    row: FolderEntryRow,
    onAction: (FolderAction) -> Unit,
) {
    // The open file: a filled tint plus weight, and "selected" for TalkBack (UX-19);
    // the tint alone is 1.2:1 on the panel.
    val current =
        if (row.current) Modifier.background(SS.colors.segmentTrack).semantics { selected = true } else Modifier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = SS.dimens.row)
                .then(current)
                .combinedClickable(
                    enabled = !row.isDirectory,
                    role = Role.Button,
                    onLongClick = { onAction(FolderAction.RowMenu(row.uri)) },
                    onClick = { onAction(FolderAction.OpenFile(row.uri)) },
                ).padding(start = 16.dp + IndentPerDepth * row.depth),
    ) {
        Icon(
            if (row.isDirectory) AppIcons.folder else AppIcons.file,
            contentDescription = null,
            tint = SS.colors.inkMuted,
            modifier = Modifier.size(18.dp),
        )
        Text(
            row.name,
            style =
                when {
                    row.isDirectory -> SS.type.label
                    row.current -> SS.type.bodyStrong
                    else -> SS.type.body
                },
            color = if (row.muted) SS.colors.inkMuted else SS.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        // A drawn dot labelled "Unsaved", so the row reads "notes.md, Unsaved" (UX-6).
        if (row.dirty) UnsavedDot(Modifier.padding(end = 8.dp))
        if (row.changedOutside) Tag(stringResource(R.string.folder_changed))
        if (row.isDirectory && row.unsavedInside > 0) Tag(stringResource(R.string.folder_unsaved, row.unsavedInside))
        if (!row.isDirectory) {
            IconButton(onClick = { onAction(FolderAction.RowMenu(row.uri)) }) {
                Icon(AppIcons.more, stringResource(R.string.nav_row_menu, row.name), tint = SS.colors.inkMuted)
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = SS.type.caption,
        color = SS.colors.caution,
        modifier =
            Modifier
                .padding(
                    end = 8.dp,
                ).background(SS.colors.cautionBg, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun Footer(onAction: (FolderAction) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        FooterButton(R.string.folder_new_file) { onAction(FolderAction.NewFileHere) }
        FooterButton(R.string.folder_show_all) { onAction(FolderAction.ToggleShowAllFiles) }
        FooterButton(R.string.folder_open_another) { onAction(FolderAction.OpenAnotherFolder) }
        FooterButton(R.string.folder_close) { onAction(FolderAction.CloseFolder) }
    }
}

@Composable
private fun FooterButton(
    label: Int,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = SS.dimens.row)) {
        Text(stringResource(label), style = SS.type.body, color = SS.colors.ink, modifier = Modifier.fillMaxWidth())
    }
}
